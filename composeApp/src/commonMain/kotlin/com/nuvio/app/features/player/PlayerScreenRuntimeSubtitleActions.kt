package com.nuvio.app.features.player

import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlinx.coroutines.launch

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem() {
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    SubtitleRepository.fetchAddonSubtitles(type, videoId)
}

/**
 * Auto-applies the highest-preference addon (our-backend) subtitle on playback start,
 * with NO manual user tap. Selection priority:
 *   1. preferred subtitle language, 2. secondary subtitle language,
 *   3. (then) any remaining language the backend returned (e.g. sv/fi),
 * picking the first match in [addonSubtitles] (which the repository already orders by
 * preference). If none of the configured targets match, fall back to the first available
 * subtitle so the user still gets subtitles instead of silence. Embedded/internal tracks
 * always win first (handled in refreshTracks); this only fires when no internal track was
 * selected and the user hasn't already made a choice.
 */
internal fun PlayerScreenRuntime.autoApplyPreferredAddonSubtitleIfNeeded() {
    // A choice was already made (restored, internal, or user) — never override it.
    if (selectedAddonSubtitleId != null) return
    if (useCustomSubtitles) return
    if (selectedSubtitleIndex != -1) return

    // Subtitles disabled by the user.
    val normalizedPreferred = normalizeLanguageCode(playerSettingsUiState.preferredSubtitleLanguage)
    if (!subtitleStyle.useForcedSubtitles &&
        normalizedPreferred == SubtitleLanguageOption.NONE &&
        playerSettingsUiState.secondaryPreferredSubtitleLanguage.isNullOrBlank()
    ) {
        return
    }

    val available = addonSubtitles
    if (available.isEmpty()) return

    val targets = preferredSubtitleTargetsForSettings(playerSettingsUiState)

    // Walk targets in priority order; the first available language wins.
    val chosen = targets.firstNotNullOfOrNull { target ->
        available.firstOrNull { sub ->
            languageMatchesPreference(trackLanguage = sub.language, targetLanguage = target)
        }
    } ?: run {
        // No configured target matched. Only fall back to "any available" when the user
        // hasn't restricted to preferred-only languages.
        if (subtitleStyle.showOnlyPreferredLanguages ||
            playerSettingsUiState.addonSubtitleStartupMode == AddonSubtitleStartupMode.PREFERRED_ONLY
        ) {
            null
        } else {
            available.firstOrNull()
        }
    } ?: return

    selectedAddonSubtitleId = chosen.id
    selectedSubtitleIndex = -1
    useCustomSubtitles = true
    persistAddonSubtitlePreference(chosen)
    playerController?.setSubtitleUri(chosen.url, language = chosen.language, label = chosen.display)
    preferredSubtitleSelectionApplied = true
}

internal fun PlayerScreenRuntime.setSubtitleDelay(delayMs: Int) {
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
    playerController?.setSubtitleDelayMs(clamped)
}

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false) {
    val subtitle = selectedAddonSubtitle ?: return
    if (!force && subtitleAutoSyncState.cues.isNotEmpty()) return
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
    scope.launch {
        val result = runCatching {
            val body = httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        }
        result.fold(
            onSuccess = { cues ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    cues = cues,
                    isLoading = false,
                    errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null,
                )
            },
            onFailure = { error ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = error.message ?: localizedSubtitleLinesLoadError(),
                )
            },
        )
    }
}

internal fun PlayerScreenRuntime.captureSubtitleAutoSyncTime() {
    subtitleAutoSyncState = subtitleAutoSyncState.copy(
        capturedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        errorMessage = null,
    )
    loadSubtitleAutoSyncCues()
}

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs)
}
