package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetailsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mobile port of NuvioTV's StreamWarmer (core/stream/StreamWarmer.kt).
 *
 * When a details screen opens, this fetches the stream list for the title in the BACKGROUND,
 * picks the top Tier-2 (debrid-CACHED, not-yet-resolved) candidate(s), resolves each to a
 * direct CDN url via the existing [DirectDebridPlaybackResolver], and stores the result in
 * [ResolvedStreamCache]. When the user later hits Play, the play path serves the pre-resolved
 * url instantly instead of doing a fresh TorBox round-trip — eliminating the slow play /
 * "could not open link" timeouts the mobile app had.
 *
 * Best-effort by design:
 *  - runs on [Dispatchers.IO]; never blocks the UI
 *  - cancellation-safe (re-throws CancellationException, swallows everything else)
 *  - deduped per (type|videoId) so re-composition / quick re-opens don't double-fetch
 *  - bounded to the top [MAX_WARM_CANDIDATES] candidates so warm creates at most a couple of
 *    TorBox round-trips per open (mirrors TV's BACKGROUND_PROBE_COUNT cap)
 *  - only warms CACHED debrid streams ([DirectDebridPlaybackResolver.shouldResolveToPlayableStream]
 *    + isDirectDebridStream) — it never triggers a download for uncached content.
 */
object StreamWarmer {
    private val log = Logger.withTag("StreamWarmer")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cap on candidates resolved per warm (limits TorBox round-trips). */
    private const val MAX_WARM_CANDIDATES = 2

    /** Per-candidate resolve bound — keep it well under the 45s play-path timeout. */
    private const val WARM_RESOLVE_TIMEOUT_MS = 30_000L

    private val mutex = Mutex()

    /** Dedup guard: (type|videoId) warms currently in flight. */
    private val inFlight = mutableSetOf<String>()

    /**
     * Fire-and-forget warm for [type]/[videoId]. Safe to call on every details open and on
     * every up-next episode change. Deduped, cancellation-safe, never throws.
     *
     * @param season / [episode] for series — forwarded to the resolver so the correct episode
     *   file is selected, and folded into the [ResolvedStreamCache] key.
     */
    fun warm(type: String, videoId: String, season: Int? = null, episode: Int? = null) {
        val normalizedType = when (type.trim().lowercase()) {
            "movie", "film" -> "movie"
            "series", "show", "tv", "tvshow" -> "series"
            else -> return
        }
        val id = videoId.trim()
        if (id.isBlank()) return

        val key = "$normalizedType/$id/${season ?: ""}/${episode ?: ""}"
        scope.launch {
            val claimed = mutex.withLock {
                if (inFlight.contains(key)) false else inFlight.add(key)
            }
            if (!claimed) return@launch
            try {
                warmInternal(normalizedType, id, season, episode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.d { "warm $key failed (best-effort, ignored): ${e.message}" }
            } finally {
                mutex.withLock { inFlight.remove(key) }
            }
        }
    }

    private suspend fun warmInternal(type: String, videoId: String, season: Int?, episode: Int?) {
        val streams = fetchTopStreams(type, videoId, season, episode)
        if (streams.isEmpty()) {
            log.d { "warm: no streams for $type/$videoId" }
            return
        }

        // Pick the top Tier-2 candidates: direct-debrid CACHED streams that have no direct url yet
        // and that we are allowed to resolve. Skip any already cached (fresh) in ResolvedStreamCache.
        val candidates = streams
            .asSequence()
            .filter { it.isDirectDebridStream && it.playableDirectUrl == null }
            .filter { DirectDebridPlaybackResolver.shouldResolveToPlayableStream(it) }
            .filter { !ResolvedStreamCache.isFresh(it.resolvedStreamCacheKey(season, episode)) }
            .take(MAX_WARM_CANDIDATES)
            .toList()

        if (candidates.isEmpty()) {
            log.d { "warm: no Tier-2 candidates to resolve for $type/$videoId (already warm or none)" }
            return
        }

        log.d { "warm: resolving ${candidates.size} Tier-2 candidate(s) for $type/$videoId" }
        for (stream in candidates) {
            val cacheKey = stream.resolvedStreamCacheKey(season, episode) ?: continue
            // Register this resolve as in-flight under the SAME key the play path computes, so that
            // a Play tapped mid-warm AWAITS this resolve instead of launching a duplicate. The warm
            // keeps its own per-candidate timeout to bound TorBox round-trips, but the in-flight
            // entry the play path may attach to is the genuine, possibly-slow resolve.
            val url = try {
                ResolvedStreamCache.resolveSingleFlight(cacheKey) {
                    val resolved = withTimeoutOrNull(WARM_RESOLVE_TIMEOUT_MS) {
                        DirectDebridPlaybackResolver.resolveToPlayableStream(
                            stream = stream,
                            season = season,
                            episode = episode,
                        )
                    }
                    when (resolved) {
                        is DirectDebridPlayableResult.Success -> resolved.stream.playableDirectUrl
                        null -> {
                            log.d { "warm: resolve timed out for key=$cacheKey" }
                            null
                        }
                        else -> {
                            log.d { "warm: resolve result=$resolved for key=$cacheKey" }
                            null
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.d { "warm: resolve threw for key=$cacheKey: ${e.message}" }
                null
            }
            if (url != null) {
                log.d { "warm: cached resolved url for key=$cacheKey" }
                // One good candidate is enough to make play instant — stop early to
                // avoid extra TorBox round-trips.
                return
            }
        }
    }

    /**
     * Fetches and flattens the stream list for [type]/[videoId] the same way the stream screen
     * does (backend catalog-addon + installed third-party Stremio addons), but lean and
     * read-only — no UI state, no plugins, no debrid cache-check side effects. Embedded streams
     * (already attached to the meta) are used when present.
     */
    private suspend fun fetchTopStreams(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> {
        val embedded = MetaDetailsRepository.findEmbeddedStreams(videoId)
        if (embedded.isNotEmpty()) return embedded

        val streamProviderEnabled =
            com.nuvio.app.features.settings.BuiltInProvidersSettingsRepository.isStreamProviderEnabled()
        if (streamProviderEnabled &&
            com.nuvio.app.core.content.ContentSourceProvider.cachedContentAddons.isEmpty()
        ) {
            // Prime the backend catalog-addon cache if a detail screen opened before Home.
            runCatching { com.nuvio.app.core.content.ContentSourceProvider.prime() }
        }

        val installedAddons = if (streamProviderEnabled) {
            com.nuvio.app.core.content.ContentSourceProvider.cachedContentAddons +
                AddonRepository.uiState.value.addons
        } else {
            AddonRepository.uiState.value.addons
        }.enabledAddons()

        val targets = installedAddons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            val supports = manifest.resources.any { resource ->
                resource.name == "stream" &&
                    resource.types.contains(type) &&
                    (resource.idPrefixes.isEmpty() ||
                        resource.idPrefixes.any { videoId.startsWith(it) })
            }
            if (!supports) return@mapNotNull null
            InstalledStreamAddonTarget(
                addonName = addon.displayTitle.ifBlank { manifest.name },
                addonId = addon.streamAddonInstanceId(manifest.id),
                manifest = manifest,
            )
        }
        if (targets.isEmpty()) return emptyList()

        // Stremio series protocol: id=<contentId>:<season>:<episode>.
        val streamId = if (
            type.equals("series", ignoreCase = true) &&
            season != null && episode != null &&
            !videoId.contains(':')
        ) {
            "$videoId:$season:$episode"
        } else {
            videoId
        }

        val result = mutableListOf<StreamItem>()
        for (addon in targets) {
            val url = com.nuvio.app.core.network.PrivateBackend.withDeviceProfile(
                buildAddonResourceUrl(
                    manifestUrl = addon.manifest.transportUrl,
                    resource = "stream",
                    type = type,
                    id = streamId,
                )
            )
            val streams = runCatchingUnlessCancelled {
                val payload = httpGetText(url)
                StreamParser.parse(
                    payload = payload,
                    addonName = addon.addonName,
                    addonId = addon.addonId,
                    addonLogo = addon.manifest.logoUrl,
                )
            }.getOrElse { emptyList() }
            result += streams
            // The backend catalog-addon is ordered first in the addon list, so as soon as we
            // have a resolvable Tier-2 candidate we can stop — keeps warm cheap.
            if (result.any { it.isDirectDebridStream && it.playableDirectUrl == null }) break
        }
        return result
    }
}
