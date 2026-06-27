package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A single external subtitle candidate to preload into the player as a selectable text track.
 * Carries a clean human [label] and language so track selection never has to fall back to the raw
 * (".zip") URL filename for display.
 */
data class ExternalSubtitleInput(
    val url: String,
    val language: String,
    val label: String,
)

interface PlayerEngineController {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun retry()
    fun setPlaybackSpeed(speed: Float)
    fun setMuted(muted: Boolean) {}
    fun getAudioTracks(): List<AudioTrack>
    fun getSubtitleTracks(): List<SubtitleTrack>
    fun selectAudioTrack(index: Int)
    fun selectSubtitleTrack(index: Int)

    /**
     * Preload a set of external (addon) subtitle tracks into the CURRENT media item as selectable
     * text tracks, WITHOUT restarting the video. This is the robust path: it builds all candidate
     * subtitle configurations up front (one media rebuild while still buffering, position/play-state
     * preserved) so that subsequently switching between them via [setSubtitleUri]/[selectSubtitleTrack]
     * is pure track selection and never re-prepares the stream. Default no-op for engines that
     * side-load natively (libmpv) or aren't track-selection based.
     */
    fun setExternalSubtitles(subtitles: List<ExternalSubtitleInput>) {}

    /**
     * Select an external subtitle by URL. [language] and [label] carry the clean human metadata from
     * the chosen [AddonSubtitle] so that if the engine has to synthesize a candidate (the rich one
     * from [setExternalSubtitles] hasn't folded yet), the resulting track still has a real language
     * (for preferred-language matching) and a clean label — never the raw download URL / ".zip".
     */
    fun setSubtitleUri(url: String, language: String = "", label: String = "")
    fun clearExternalSubtitle()
    fun clearExternalSubtitleAndSelect(trackIndex: Int)
    fun applySubtitleStyle(style: SubtitleStyleState) {}
    fun setSubtitleDelayMs(delayMs: Int) {}
    fun configureIosVideoOutput(settings: PlayerSettingsUiState) {}
}

internal fun sanitizePlaybackHeaders(headers: Map<String, String>?): Map<String, String> {
    val rawHeaders = headers ?: return emptyMap()
    if (rawHeaders.isEmpty()) return emptyMap()

    val sanitized = LinkedHashMap<String, String>(rawHeaders.size)
    rawHeaders.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        if (key.equals("Range", ignoreCase = true)) return@forEach
        sanitized[key] = value
    }
    return sanitized
}

internal fun sanitizePlaybackResponseHeaders(headers: Map<String, String>?): Map<String, String> {
    val rawHeaders = headers ?: return emptyMap()
    if (rawHeaders.isEmpty()) return emptyMap()

    val sanitized = LinkedHashMap<String, String>(rawHeaders.size)
    rawHeaders.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        sanitized[key] = value
    }
    return sanitized
}

@Composable
expect fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String? = null,
    sourceHeaders: Map<String, String> = emptyMap(),
    sourceResponseHeaders: Map<String, String> = emptyMap(),
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    streamType: String? = null,
    useYoutubeChunkedPlayback: Boolean = false,
    modifier: Modifier = Modifier,
    playWhenReady: Boolean = true,
    resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    useNativeController: Boolean = false,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
)
