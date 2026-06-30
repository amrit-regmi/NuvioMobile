package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.PrivateBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-scope manager for the "download to TorBox" flow. Mirrors NuvioTV's
 * `DebridDownloadManager`: only ONE title may be downloading at a time, the active title is
 * tracked across screens via [activePrepare], and live progress is published via [progress].
 *
 * It owns the full prepare → poll lifecycle so the detail screen can drive its download button
 * purely by reading these StateFlows (no params threaded through the composable tree):
 *   - [start] POSTs /prepare, handles the immediate status, then polls /status every 4s up to
 *     30 minutes, exiting on cached==true || has_url==true.
 *   - [cancelAndClearPrepare] fires DELETE /prepare (fire-and-forget) and clears state.
 *   - [resumeIfActiveFor] re-attaches the polling loop when the user returns to a title that is
 *     still the active download (and no loop is running).
 *
 * On completion we call [StreamsRepository.refreshAfterPrewarm] so an on-screen stream list
 * re-fetches and the cache pill flips (mobile has no public StreamWarmer eviction hook — this is
 * the existing post-prewarm refresh mechanism reused; see report note).
 */
object DebridDownloadManager {
    private val log = Logger.withTag("DebridDownloadManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val POLL_INTERVAL_MS = 4_000L
    private const val MAX_POLL_DURATION_MS = 30L * 60L * 1_000L // 30 minutes

    /** The single title currently being prepared at app scope. type = "movie"|"series". */
    data class ActivePrepare(
        val type: String,
        val videoId: String,
        val title: String,
    )

    /** Live progress for the active download, mirroring the detail-screen state fields. */
    data class DownloadProgress(
        val type: String,
        val videoId: String,
        val isPreparing: Boolean = true,
        val percent: Float? = null,
        val queued: Boolean = false,
        val queuePosition: Int? = null,
        val seedCount: Int? = null,
        val speedMbps: Double? = null,
        val etaMinutes: Int? = null,
        val ready: Boolean = false,
        val streamableNow: Boolean = false,
        /** Set when a terminal non-success status (no_seeders/slots_full/error/timeout) occurred. */
        val errorMessage: String? = null,
    )

    private val _activePrepare = MutableStateFlow<ActivePrepare?>(null)
    val activePrepare: StateFlow<ActivePrepare?> = _activePrepare.asStateFlow()

    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress.asStateFlow()

    private var pollJob: Job? = null

    private fun isAuthenticated(): Boolean {
        val state = AuthRepository.state.value
        return state is AuthState.Authenticated && !state.isAnonymous
    }

    private fun normalizeType(type: String): String = when (type.trim().lowercase()) {
        "movie", "film" -> "movie"
        "series", "show", "tv", "tvshow" -> "series"
        else -> type.trim().lowercase()
    }

    /** True if [type]/[videoId] is the currently-active download. */
    fun isActiveFor(type: String, videoId: String): Boolean {
        val active = _activePrepare.value ?: return false
        return active.type == normalizeType(type) && active.videoId == videoId.trim()
    }

    /** Register a prepare as the current active one (used internally by [start]). */
    fun setPrepareActive(type: String, videoId: String, title: String) {
        _activePrepare.value = ActivePrepare(normalizeType(type), videoId.trim(), title)
    }

    /** Clear the active registration WITHOUT a backend DELETE (use on successful completion). */
    fun clearPrepare() {
        _activePrepare.value = null
    }

    /**
     * Cancel the active prepare: stops the poll loop, clears state, and fires a fire-and-forget
     * DELETE /prepare so the torrent is removed from TorBox. Call on explicit user cancellation.
     */
    fun cancelAndClearPrepare() {
        val current = _activePrepare.value
        pollJob?.cancel()
        pollJob = null
        _activePrepare.value = null
        _progress.value = null
        if (current != null) {
            scope.launch {
                runCatching { CatalogDownloadApi.cancelPrepare(current.type, current.videoId) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    /**
     * Start preparing [type]/[videoId]. Cancels (and DELETEs) any prior active download first,
     * registers this one, POSTs /prepare, then polls /status until cached or timeout.
     * Idempotent for the same title while a loop is active.
     */
    fun start(type: String, videoId: String, title: String) {
        if (PrivateBackend.baseUrl.isBlank()) return
        if (!isAuthenticated()) return
        val normType = normalizeType(type)
        val id = videoId.trim()
        if (id.isBlank()) return

        // Cancel + DELETE any previously-active item (different title) before taking over.
        val prior = _activePrepare.value
        if (prior != null && !(prior.type == normType && prior.videoId == id)) {
            cancelAndClearPrepare()
        }

        pollJob?.cancel()
        setPrepareActive(normType, id, title)
        _progress.value = DownloadProgress(type = normType, videoId = id, isPreparing = true)

        pollJob = scope.launch {
            try {
                when (val result = CatalogDownloadApi.prepare(normType, id)) {
                    is CatalogDownloadApi.PrepareResult.Failure -> {
                        clearPrepare()
                        _progress.value = _progress.value?.copy(
                            isPreparing = false,
                            errorMessage = "Failed to queue stream",
                        )
                        return@launch
                    }
                    is CatalogDownloadApi.PrepareResult.Success -> {
                        when (result.dto.status) {
                            "already_cached" -> {
                                onCachedReady(normType, id)
                                return@launch
                            }
                            "no_seeders", "no_valid_hash" -> {
                                clearPrepare()
                                _progress.value = _progress.value?.copy(
                                    isPreparing = false,
                                    errorMessage = "No active seeders — this title may not be available right now.",
                                )
                                return@launch
                            }
                            "slots_full" -> {
                                clearPrepare()
                                _progress.value = _progress.value?.copy(
                                    isPreparing = false,
                                    errorMessage = "TorBox download slots are full. Cancel an existing download to add more.",
                                )
                                return@launch
                            }
                            else -> {
                                // "queued" / success / unknown — proceed to poll.
                                _progress.value = _progress.value?.copy(
                                    isPreparing = true,
                                    etaMinutes = result.dto.etaMinutes,
                                )
                            }
                        }
                    }
                }
                pollStatusUntilCached(normType, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "start failed for $normType/$id" }
                clearPrepare()
                _progress.value = _progress.value?.copy(
                    isPreparing = false,
                    errorMessage = "Failed to queue stream",
                )
            }
        }
    }

    /** Re-attach the poll loop if [type]/[videoId] is the active download but no loop is running. */
    fun resumeIfActiveFor(type: String, videoId: String) {
        val normType = normalizeType(type)
        val id = videoId.trim()
        if (!isActiveFor(normType, id)) return
        if (pollJob?.isActive == true) return
        _progress.value = (_progress.value ?: DownloadProgress(type = normType, videoId = id))
            .copy(isPreparing = true)
        pollJob = scope.launch {
            runCatching { pollStatusUntilCached(normType, id) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    private fun onCachedReady(type: String, videoId: String) {
        clearPrepare()
        _progress.value = _progress.value?.copy(
            isPreparing = false,
            ready = true,
            percent = 100f,
            queued = false,
            queuePosition = null,
            seedCount = null,
            speedMbps = null,
            etaMinutes = 0,
            streamableNow = false,
        )
        // The title is now cached — update the prewarm cache hint so the detail Download button
        // gates itself off (nothing left to download).
        CatalogPrewarmService.markCached(type, videoId)
        // Reuse the existing post-prewarm refresh so an on-screen stream list re-fetches and the
        // cache pill flips to cached (mobile has no public StreamWarmer eviction hook).
        StreamsRepository.refreshAfterPrewarm(type, videoId)
    }

    private suspend fun pollStatusUntilCached(type: String, videoId: String) {
        val startMs = epochMs()
        while (epochMs() - startMs < MAX_POLL_DURATION_MS) {
            delay(POLL_INTERVAL_MS)
            // The user started a different title elsewhere — this loop is stale, exit quietly.
            if (!isActiveFor(type, videoId)) {
                if (_progress.value?.videoId == videoId) {
                    _progress.value = _progress.value?.copy(isPreparing = false, streamableNow = false)
                }
                return
            }
            val status = try {
                CatalogDownloadApi.status(type, videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.d { "status poll threw (continuing): ${e.message}" }
                continue
            } ?: continue

            val item = status.items.firstOrNull() ?: continue
            val cached = item.cached == true
            val hasUrl = item.hasUrl == true
            if (cached || hasUrl) {
                onCachedReady(type, videoId)
                return
            }
            val percent = item.progressPct?.toFloat()
            val isQueued = item.downloadState.equals("queued", ignoreCase = true)
            val streamableNow = item.downloadState == "downloading" && percent != null && percent > 0f
            _progress.value = _progress.value?.copy(
                isPreparing = true,
                percent = percent,
                queued = isQueued,
                queuePosition = if (isQueued) item.queuePosition else null,
                seedCount = item.seeds,
                speedMbps = item.downloadSpeedMbps,
                etaMinutes = item.etaSeconds?.let { (it / 60).coerceAtLeast(1) }
                    ?: _progress.value?.etaMinutes,
                streamableNow = streamableNow,
            )
        }
        // 30-min timeout
        clearPrepare()
        _progress.value = _progress.value?.copy(
            isPreparing = false,
            streamableNow = false,
            errorMessage = "Stream preparation timed out",
        )
    }
}
