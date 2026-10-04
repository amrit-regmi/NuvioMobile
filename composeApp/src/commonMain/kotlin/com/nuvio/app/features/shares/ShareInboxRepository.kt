package com.nuvio.app.features.shares

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ShareInboxUiState(
    val items: List<InboxItem> = emptyList(),
    val isLoading: Boolean = false,
) {
    /** Unread badge count — item count, per the plan ("item count is the unread badge count"). */
    val badgeCount: Int get() = items.size
}

/**
 * Polls `GET /shares/inbox` (pending content shares + incoming permission requests) while the
 * app is in the foreground, and exposes the result for the home top-bar bell + its sheet.
 *
 * Mirrors [com.nuvio.app.core.network.NetworkStatusRepository]'s scope/`ensureStarted` idiom.
 * Fallback-only delivery for v1 (no push yet wired end-to-end) — this periodic poll plus an
 * explicit refresh on app foreground ([com.nuvio.app.core.sync.AppForegroundMonitor], collected
 * from [com.nuvio.app.App]) is how the inbox badge stays current.
 */
object ShareInboxRepository {
    private const val POLL_INTERVAL_MS = 30_000L

    private val log = Logger.withTag("ShareInbox")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(ShareInboxUiState())
    val uiState: StateFlow<ShareInboxUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    /** Starts the periodic poll loop. Idempotent — safe to call from every entry point. */
    fun ensureStarted() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (true) {
                refreshNow()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Forces an immediate refresh (e.g. on app foreground). Best-effort, never throws. */
    suspend fun refreshNow() {
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous) {
            _uiState.value = ShareInboxUiState()
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true)
        val items = runCatching { SharesApi.fetchInbox() }
            .onFailure { log.w(it) { "inbox refresh failed" } }
            .getOrDefault(_uiState.value.items)
        _uiState.value = ShareInboxUiState(items = items, isLoading = false)
    }

    /** Optimistically removes [id] from the in-memory list (after a respond/dismiss call). */
    private fun removeLocally(id: String) {
        _uiState.value = _uiState.value.copy(items = _uiState.value.items.filterNot { it.id == id })
    }

    /** "Add to Watchlist" / dismiss for a content share. Always removes the row locally first. */
    fun respondToShare(id: String, added: Boolean) {
        removeLocally(id)
        scope.launch {
            runCatching { SharesApi.respondToShare(id, added) }
                .onFailure { log.w(it) { "respondToShare failed for $id" } }
        }
    }

    /** Allow/deny for an incoming "receive from" permission request. */
    fun respondToPermissionRequest(id: String, allow: Boolean) {
        removeLocally(id)
        scope.launch {
            runCatching { SharesApi.respondToPermissionRequest(id, allow) }
                .onFailure { log.w(it) { "respondToPermissionRequest failed for $id" } }
        }
    }
}
