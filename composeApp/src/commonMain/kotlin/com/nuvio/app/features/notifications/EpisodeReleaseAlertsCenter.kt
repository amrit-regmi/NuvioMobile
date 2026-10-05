package com.nuvio.app.features.notifications

import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A scheduled episode-release request whose release date has passed and isn't dismissed yet. */
data class FiredEpisodeAlert(
    val requestId: String,
    val title: String,
    val body: String,
    val deepLinkUrl: String,
    val backdropUrl: String?,
)

/**
 * Surfaces fired episode-release alerts for the unified notification bell (see
 * [com.nuvio.app.features.shares.ShareInboxBell]), alongside the shares inbox.
 *
 * Deliberately separate from [EpisodeReleaseNotificationsRepository] — that object owns
 * scheduling; this one only surfaces what has already fired. There is no fire-time hook we can
 * rely on on both platforms (iOS has no app-code equivalent of Android's WorkManager Worker
 * running when a local notification actually fires), so "fired" is reconstructed by comparing
 * each scheduled request's release date against today, on every app foreground — the same
 * foreground-reconciliation pattern already used for the shares inbox poll.
 *
 * Date-level granularity only (no exact hour/minute check): a request is "fired" once its
 * release date is today or earlier, which is simple, needs no new platform clock hook, and is
 * an acceptable approximation given this is a backstop UI, not the real OS notification.
 */
object EpisodeReleaseAlertsCenter {
    private val _firedUnseen = MutableStateFlow<List<FiredEpisodeAlert>>(emptyList())
    val firedUnseen: StateFlow<List<FiredEpisodeAlert>> = _firedUnseen.asStateFlow()

    /** Recomputes [firedUnseen] from the current schedule + dismissed-ids. Call on app foreground
     * and once at startup, alongside [EpisodeReleaseNotificationsRepository.ensureLoaded]. */
    fun reconcile() {
        // Self-sufficient regardless of call order relative to the repository's own loading —
        // matches every other public entry point on EpisodeReleaseNotificationsRepository,
        // which all call ensureLoaded() first too. Idempotent (no-op once already loaded).
        EpisodeReleaseNotificationsRepository.ensureLoaded()

        if (!EpisodeReleaseNotificationsRepository.alertsEligible()) {
            _firedUnseen.value = emptyList()
            return
        }

        val today = CurrentDateProvider.todayIsoDate()
        val dismissedIds = EpisodeReleaseNotificationsRepository.dismissedFiredIdsSnapshot()
        _firedUnseen.value = EpisodeReleaseNotificationsRepository.scheduledRequestsSnapshot()
            .filter { it.releaseDateIso <= today && it.requestId !in dismissedIds }
            .map { request ->
                FiredEpisodeAlert(
                    requestId = request.requestId,
                    title = request.notificationTitle,
                    body = request.notificationBody,
                    deepLinkUrl = request.deepLinkUrl,
                    backdropUrl = request.backdropUrl,
                )
            }
    }

    /** User dismissed a fired alert from the bell's sheet. Removes it immediately and persists
     * the dismissal so it doesn't reappear on the next reconcile. */
    fun dismiss(requestId: String) {
        _firedUnseen.value = _firedUnseen.value.filterNot { it.requestId == requestId }
        EpisodeReleaseNotificationsRepository.markFiredAlertDismissed(requestId)
    }
}
