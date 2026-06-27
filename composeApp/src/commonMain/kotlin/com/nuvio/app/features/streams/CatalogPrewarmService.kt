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
