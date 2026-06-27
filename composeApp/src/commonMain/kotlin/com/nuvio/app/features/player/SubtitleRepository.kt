package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpGetText
import co.touchlab.kermit.Logger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_no_subtitles_found
import nuvio.composeapp.generated.resources.player_addon_subtitle_display_format
import org.jetbrains.compose.resources.getString

object SubtitleRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitles: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var activeFetchJob: Job? = null

    // r15: identity of the last successfully-fetched (or in-flight) request, so a repeated fetch for
    // the SAME content (e.g. the details-open subtitle prewarm followed by the player's lazy
    // auto-fetch) is a no-op instead of clearing + re-fetching. This keeps addonSubtitles populated
    // continuously from details-open through player-open, so the player can bundle the candidates
    // into the INITIAL MediaItem (first subtitle switch = pure track selection, no re-prepare).
    private var lastFetchKey: String? = null

    // r16: persistent, content-keyed cache of resolved subtitle lists. The player clears the visible
    // list ([clear]) on every source open to avoid showing a previous title's subs, which used to
    // force a full (~tens-of-seconds SubDL) re-fetch even when REPLAYING the same title. This map
    // survives player open/close (only wiped on sign-out via [clearAll]) so a replay — or a quick
    // re-open after browsing back — restores the candidate list INSTANTLY, with no network round-trip
    // and therefore bundled into the initial prepare (no late fold / stream restart). Mirrors how
    // ResolvedStreamCache makes the stream url instant on replay.
    private val cacheLock = SynchronizedObject()
    private val contentCache = LinkedHashMap<String, List<AddonSubtitle>>()
    private const val MAX_CONTENT_CACHE_ENTRIES = 50

    /**
     * r15: prefetch addon subtitles at details-open so the candidate list is ready BEFORE the player
     * composes. Best-effort, deduped by content key — never clears an existing successful result.
     */
    fun prewarmAddonSubtitles(type: String, videoId: String) {
        val key = "${canonicalSubtitleType(type)}|$videoId"
        val alreadyCached = synchronized(cacheLock) { contentCache.containsKey(key) }
        Logger.i("SubtitleRepo: prewarm key=$key alreadyCached=$alreadyCached lastFetchKey=$lastFetchKey")
        if (key == lastFetchKey) return
        fetchAddonSubtitles(type, videoId)
    }

    /**
     * Pulsating-phase fast path: synchronously restore the resolved candidate list from the
     * persistent cache WITHOUT any network or addon dependency. Returns true if the content was
     * cached (typically from the details-open prewarm) and the visible list is now populated.
     *
     * This is the deterministic fix for "subs bundle at initial prepare". The player's source-open
     * [clear] wipes the visible list the moment the stream resolves; the previous re-populate was
     * gated on installed addons being loaded ([addonSubtitleFetchKey]), which frequently misses the
     * ~3s initial-prepare window — so the player prepared with 0 candidates and folded later (= the
     * stream-restart / second-spinner bug). A cache hit here needs neither addons nor the network,
     * so it lands instantly inside the bundle window. A miss leaves all state untouched so the
     * addon-gated [fetchAddonSubtitles] network path still runs (and we accept the rare on-demand fold).
     */
    fun restoreFromCache(type: String, videoId: String): Boolean {
        val key = "${canonicalSubtitleType(type)}|$videoId"
        val cached = synchronized(cacheLock) { contentCache[key] } ?: run {
            Logger.i("SubtitleRepo: restoreFromCache MISS key=$key (will network-fetch when addons load)")
            return false
        }
        Logger.i("SubtitleRepo: restoreFromCache HIT key=$key ${cached.size} langs=${cached.map { it.language }}")
        lastFetchKey = key
        activeFetchJob?.cancel()
        _error.value = null
        _isLoading.value = false
        _addonSubtitles.value = cached
        return true
    }

    fun fetchAddonSubtitles(type: String, videoId: String) {
        val requestType = canonicalSubtitleType(type)
        val key = "$requestType|$videoId"
        // r15: dedup — if we've already loaded (or are loading) this exact content, don't clear and
        // re-fetch. Prevents the player's lazy auto-fetch from wiping the details-open prewarm result.
        if (key == lastFetchKey && (_isLoading.value || _addonSubtitles.value.isNotEmpty())) {
            return
        }
        // r16: persistent cache hit — restore the resolved list INSTANTLY (no network). This is the
        // replay / quick re-open path: the player cleared the visible list on source-open, but the
        // content's subtitles were already resolved earlier this session, so there's nothing to fetch.
        val cached = synchronized(cacheLock) { contentCache[key] }
        Logger.i("SubtitleRepo: fetch key=$key cacheHit=${cached != null} (size=${cached?.size ?: -1})")
        if (cached != null) {
            lastFetchKey = key
            activeFetchJob?.cancel()
            _error.value = null
            _isLoading.value = false
            _addonSubtitles.value = cached
            return
        }
        lastFetchKey = key
        activeFetchJob?.cancel()
        activeFetchJob = scope.launch {
            _isLoading.value = true
            _error.value = null
            _addonSubtitles.value = emptyList()

            // Private-backend fork: subtitles come from OUR backend's catalog-addon
            // (`subtitles` resource), not arbitrary installed Stremio addons. The subtitle
            // provider toggle (shared profile setting) gates whether we fetch them at all.
            val subtitleProviderEnabled =
                com.nuvio.app.features.settings.BuiltInProvidersSettingsRepository.isSubtitleProviderEnabled()
            val contentAddons = com.nuvio.app.core.content.ContentSourceProvider
                .cachedContentAddons.enabledAddons()
            val installedAddons = AddonRepository.uiState.value.addons.enabledAddons()
            // Merge backend content addons (when enabled) with any other installed subtitle
            // addons, de-duplicating by manifest transport URL.
            val addons = buildList {
                if (subtitleProviderEnabled) addAll(contentAddons)
                addAll(installedAddons)
            }.distinctBy { it.manifest?.transportUrl ?: it.manifestUrl }

            // #87 / api_bridge "best-subtitle-per-language": when the built-in subtitle provider is
            // ON, ask OUR backend for the prewarmed BEST subtitle per language (≤3, already
            // moviehash/release-matched and ordered primary→secondary→en) via `/subtitles/best`.
            // Per the frozen contract, when ON the player exposes ONLY these ≤3 entries — NOT the
            // full variant/language list — so they bundle at the initial prepare and every switch is
            // pure track-selection. A true miss (endpoint absent / empty) falls through to the full
            // `/subtitles` list below so the user is never left with nothing.
            if (subtitleProviderEnabled) {
                val best = mutableListOf<AddonSubtitle>()
                for (addon in contentAddons) {
                    val manifest = addon.manifest ?: continue
                    val subtitleResource =
                        manifest.resources.find { it.name.isSubtitleResourceName() } ?: continue
                    if (!subtitleResource.supportsSubtitleType(requestType, videoId)) continue
                    val bestUrl = buildAddonResourceUrl(
                        manifestUrl = manifest.transportUrl,
                        resource = "subtitles/best",
                        type = requestType,
                        id = videoId,
                    )
                    try {
                        val response = withContext(Dispatchers.Default) { httpGetText(bestUrl) }
                        val bestArray = json.parseToJsonElement(response)
                            .jsonObject["best"]?.jsonArray ?: continue
                        for (element in bestArray) {
                            val obj = element.jsonObject
                            val url = obj.stringValue("url") ?: continue
                            // Contract `lang` is 3-letter ISO-639-2 (eng/swe/fin); map to our codes.
                            val rawLang = obj.stringValue("lang") ?: "unknown"
                            val normalizedLang = when (rawLang.lowercase()) {
                                "eng" -> "en"
                                "swe" -> "sv"
                                "fin" -> "fi"
                                else -> normalizeLanguageCode(rawLang) ?: rawLang
                            }
                            val languageLabel = getLanguageLabelForCode(normalizedLang)
                            best.add(
                                AddonSubtitle(
                                    id = "${manifest.id}_best_${normalizedLang}_${best.size}",
                                    url = url,
                                    language = normalizedLang,
                                    display = getString(
                                        Res.string.player_addon_subtitle_display_format,
                                        languageLabel,
                                        addon.displayTitle,
                                    ),
                                    addonName = addon.displayTitle,
                                )
                            )
                        }
                        // First backend addon that returns a best set wins — keep its server-side
                        // primary→secondary→en order (= auto-apply priority); do NOT re-sort.
                        if (best.isNotEmpty()) break
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                    }
                }
                if (best.isNotEmpty()) {
                    _addonSubtitles.value = best
                    synchronized(cacheLock) {
                        contentCache[key] = best
                        while (contentCache.size > MAX_CONTENT_CACHE_ENTRIES) {
                            val eldest = contentCache.keys.firstOrNull() ?: break
                            contentCache.remove(eldest)
                        }
                    }
                    _isLoading.value = false
                    Logger.i("SubtitleRepo: best HIT key=$key cached ${best.size} langs=${best.map { it.language }}")
                    return@launch
                }
                Logger.i("SubtitleRepo: best MISS key=$key → falling through to full /subtitles list")
                // best miss → fall through to the full-list path below.
            }

            val allSubs = mutableListOf<AddonSubtitle>()

            for (addon in addons) {
                val manifest = addon.manifest ?: continue
                val subtitleResource = manifest.resources.find { it.name.isSubtitleResourceName() } ?: continue
                if (!subtitleResource.supportsSubtitleType(requestType, videoId)) continue

                val subtitleUrl = buildAddonResourceUrl(
                    manifestUrl = manifest.transportUrl,
                    resource = "subtitles",
                    type = requestType,
                    id = videoId,
                )

                try {
                    val response = withContext(Dispatchers.Default) {
                        httpGetText(subtitleUrl)
                    }
                    val parsed = json.parseToJsonElement(response).jsonObject
                    val subtitlesArray = parsed["subtitles"]?.jsonArray ?: continue

                    for (element in subtitlesArray) {
                        val obj = element.jsonObject
                        val id = obj.stringValue("id")
                            ?: "${manifest.id}_${allSubs.size}"
                        val url = obj.stringValue("url") ?: continue
                        // Fix 3: never let a raw filename/URL (e.g. SubDL's ".../subtitle/NNN.zip")
                        // leak into the label. Sanitize the candidate language string first, then
                        // resolve to a clean human language name.
                        val rawLang = obj.subtitleLanguage().cleanLanguageCandidate() ?: "unknown"
                        val normalizedLang = normalizeLanguageCode(rawLang) ?: rawLang
                        // Prefer the normalized code for the visible name so we always show a real
                        // language (English / Suomi-Finnish / Svenska-Swedish), never ".zip".
                        val languageLabel = getLanguageLabelForCode(normalizedLang)

                        allSubs.add(
                            AddonSubtitle(
                                id = id,
                                url = url,
                                language = normalizedLang,
                                display = getString(
                                    Res.string.player_addon_subtitle_display_format,
                                    languageLabel,
                                    addon.displayTitle,
                                ),
                                addonName = addon.displayTitle,
                            )
                        )
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                }
            }

            // Respect the preferred-subtitle-language setting: surface matching languages
            // first, then everything else (mirrors NuvioTV's preferred-language ordering).
            val preferredLanguage = normalizeLanguageCode(
                PlayerSettingsRepository.uiState.value.preferredSubtitleLanguage,
            )?.takeIf { it != SubtitleLanguageOption.NONE && it != SubtitleLanguageOption.FORCED }
            val secondaryLanguage = PlayerSettingsRepository.uiState.value.secondaryPreferredSubtitleLanguage
                ?.let { normalizeLanguageCode(it) }
                ?.takeIf { it != SubtitleLanguageOption.NONE && it != SubtitleLanguageOption.FORCED }
            val orderedSubs = if (preferredLanguage == null && secondaryLanguage == null) {
                allSubs.distinctBy { "${it.language}|${it.url}" }
            } else {
                allSubs
                    .distinctBy { "${it.language}|${it.url}" }
                    .sortedBy { sub ->
                        when (sub.language) {
                            preferredLanguage -> 0
                            secondaryLanguage -> 1
                            else -> 2
                        }
                    }
            }

            _addonSubtitles.value = orderedSubs
            // r16: cache a non-empty result so a later replay/re-open of this content is instant.
            // Empty/error results are NOT cached, so we retry the fetch next time.
            if (orderedSubs.isNotEmpty()) {
                synchronized(cacheLock) {
                    contentCache[key] = orderedSubs
                    while (contentCache.size > MAX_CONTENT_CACHE_ENTRIES) {
                        val eldest = contentCache.keys.firstOrNull() ?: break
                        contentCache.remove(eldest)
                    }
                }
            }
            if (orderedSubs.isEmpty() && addons.any { it.manifest?.resources?.any { r -> r.name.isSubtitleResourceName() } == true }) {
                _error.value = getString(Res.string.compose_player_no_subtitles_found)
            }
            _isLoading.value = false
        }
    }

    /**
     * Clears the VISIBLE subtitle list (called by the player on every source-open so a previous
     * title's subs never leak into a new one). r16: this deliberately KEEPS [contentCache] so that
     * replaying / re-opening the same content restores its list instantly from cache instead of
     * re-fetching. Use [clearAll] for sign-out, where cached results must not survive.
     */
    fun clear() {
        activeFetchJob?.cancel()
        lastFetchKey = null
        _addonSubtitles.value = emptyList()
        _isLoading.value = false
        _error.value = null
    }

    /** Full reset incl. the persistent cache — sign-out only (never replay another account's subs). */
    fun clearAll() {
        clear()
        synchronized(cacheLock) { contentCache.clear() }
    }
}

private fun canonicalSubtitleType(type: String): String =
    if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()

private fun String.isSubtitleResourceName(): Boolean =
    equals("subtitles", ignoreCase = true) || equals("subtitle", ignoreCase = true)

private fun AddonResource.supportsSubtitleType(type: String, videoId: String): Boolean {
    val canonical = canonicalSubtitleType(type)
    val typeMatches = types.isEmpty() || types.any { canonicalSubtitleType(it).equals(canonical, ignoreCase = true) }
    if (!typeMatches) return false
    return idPrefixes.isEmpty() || idPrefixes.any { prefix -> videoId.startsWith(prefix) }
}

/**
 * Fix 3: strip URL/filename noise out of a candidate language string. Backends sometimes put a
 * raw subtitle path (e.g. "https://dl.subdl.com/subtitle/3098041.zip") in the label/lang field;
 * such a value must never be shown to the user as a "language". Returns null when the candidate is
 * clearly a URL/path/archive name so the caller falls back to "unknown".
 */
private fun String?.cleanLanguageCandidate(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val looksLikeUrlOrFile = value.contains("://") ||
        value.contains('/') ||
        value.endsWith(".zip", ignoreCase = true) ||
        value.endsWith(".srt", ignoreCase = true) ||
        value.endsWith(".vtt", ignoreCase = true) ||
        value.endsWith(".ass", ignoreCase = true) ||
        value.endsWith(".ssa", ignoreCase = true)
    return if (looksLikeUrlOrFile) null else value
}

private fun JsonObject.subtitleLanguage(): String? =
    stringValue("lang")
        ?: stringValue("language")
        ?: stringValue("languageCode")
        ?: stringValue("locale")
        ?: stringValue("label")

private fun JsonObject.stringValue(name: String): String? =
    this[name]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }
