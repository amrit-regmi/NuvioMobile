package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.BackendAuth
import com.nuvio.app.core.network.PrivateBackend
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fire-and-forget client for the backend details-open prewarm endpoint:
 *   POST {backend}/catalog-addon/prewarm/{type}/{video_id}
 *
 * Calling it the moment the details panel opens pre-resolves the top stream
 * candidate(s) to a direct CDN url (Tier-1) and bootstraps en/sv/fi subtitles,
 * so the subsequent /stream returns instantly-playable streams.
 *
 * Contract (api_bridge.md, 2026-06-26):
 *  - type     = "movie" | "series"
 *  - video_id = "tt1234567" (movie) | "tt1234567:1:8" (series S1E8); tmdb: ids accepted.
 *  - Auth: same as every catalog-addon data endpoint — the Supabase user Bearer token
 *    (host-matched via BackendAuth.authHeadersFor). No token → 401.
 *  - Best-effort: failures must NEVER block the UI.
 */
object CatalogPrewarmService {
    private val log = Logger.withTag("CatalogPrewarm")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** Dedup guard: prewarm keys (type/video_id) currently in flight. */
    private val inFlight = mutableSetOf<String>()

    /** A finished prewarm. videoId is the raw form passed to [prewarm] ("tt123" or "tt123:S:E"). */
    data class PrewarmCompletion(val type: String, val videoId: String, val warmed: Boolean, val subsReady: Boolean)

    private val _completions = MutableSharedFlow<PrewarmCompletion>(extraBufferCapacity = 16)
    /** Emitted whenever a prewarm POST returns successfully. Observers can refresh the stream list. */
    val completions: SharedFlow<PrewarmCompletion> = _completions.asSharedFlow()

    /**
     * Last-known per-title cache hint from prewarm, keyed "type/videoId" (e.g. "movie/tt123",
     * "series/tt123:1:8"). value: true = a cached/playable stream exists (nothing to download →
     * hide the Download button); false = not cached (offer Download); key absent = unknown yet.
     * A StateFlow (not the SharedFlow) so a late-subscribing UI still reads the latest value.
     */
    private val _cacheHints = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val cacheHints: StateFlow<Map<String, Boolean>> = _cacheHints.asStateFlow()

    /** Build the [cacheHints] map key for a (type, videoId) pair. */
    fun cacheHintKey(type: String, videoId: String): String = "$type/$videoId"

    /**
     * Mark a title as cached (e.g. a download just finished) so the detail Download button hides
     * without waiting for a fresh prewarm. type is the normalized "movie"/"series" form.
     */
    fun markCached(type: String, videoId: String) {
        _cacheHints.update { it + (cacheHintKey(type, videoId) to true) }
    }

    private fun isAuthenticated(): Boolean {
        val state = AuthRepository.state.value
        return state is AuthState.Authenticated && !state.isAnonymous
    }

    /**
     * Fire-and-forget prewarm for [type]/[videoId]. Safe to call on every panel open.
     * Deduped against duplicate in-flight calls; cancellation-safe; never throws.
     */
    fun prewarm(type: String, videoId: String) {
        if (PrivateBackend.baseUrl.isBlank()) return
        val normalizedType = when (type.trim().lowercase()) {
            "movie", "film" -> "movie"
            "series", "show", "tv", "tvshow" -> "series"
            else -> return
        }
        val id = videoId.trim()
        if (id.isBlank()) return
        if (!isAuthenticated()) return

        val key = "$normalizedType/$id"
        scope.launch {
            val claimed = mutex.withLock {
                if (inFlight.contains(key)) false else inFlight.add(key)
            }
            if (!claimed) return@launch
            try {
                val url = "${PrivateBackend.catalogAddonUrl}/prewarm/$normalizedType/$id"
                val headers = BackendAuth.authHeadersFor(url)
                if (!headers.containsKey("Authorization")) return@launch
                val body = httpPostJsonWithHeaders(url, "", headers)
                log.d { "Prewarmed $key → ${body.take(200)}" }
                // Signal completion so an open stream list can refresh its server-computed fields
                // (subtitleLanguages / audioLanguages / cacheStatus). Lightweight contains-checks
                // avoid pulling a JSON parser onto this best-effort path.
                val warmed = body.contains("\"warmed\":true")
                // Record the cache hint so the detail-screen Download button can gate itself:
                // warmed → a cached/playable stream exists (hide Download); else not cached (offer it).
                _cacheHints.update { it + (key to warmed) }
                _completions.tryEmit(
                    PrewarmCompletion(
                        type = normalizedType,
                        videoId = id,
                        warmed = warmed,
                        subsReady = body.contains("\"subs_ready\":true"),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.d { "Prewarm $key failed (best-effort, ignored): ${e.message}" }
            } finally {
                mutex.withLock { inFlight.remove(key) }
            }
        }
    }
}
