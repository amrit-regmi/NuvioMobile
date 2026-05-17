package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.nuvio.app.core.platform.commandQtNativePlayer
import com.nuvio.app.core.platform.playQtNativeMedia

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val latestOnControllerReady = rememberUpdatedState(onControllerReady)
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val latestSourceUrl = rememberUpdatedState(sourceUrl)
    val latestSourceHeaders = rememberUpdatedState(sourceHeaders)
    val nativePlaybackAvailable = remember { mutableStateOf(false) }
    val controller = remember {
        object : PlayerEngineController {
            override fun play() {
                commandQtNativePlayer("resume", 0.0)
            }

            override fun pause() {
                commandQtNativePlayer("pause", 0.0)
            }

            override fun seekTo(positionMs: Long) {
                commandQtNativePlayer("seekTo", positionMs.toDouble())
            }

            override fun seekBy(offsetMs: Long) {
                commandQtNativePlayer("seekBy", offsetMs.toDouble())
            }

            override fun retry() {
                playQtNativeMedia(
                    latestSourceUrl.value,
                    latestSourceHeaders.value.toHeadersJson(),
                    0.0,
                )
            }

            override fun setPlaybackSpeed(speed: Float) = Unit
            override fun getAudioTracks(): List<AudioTrack> = emptyList()
            override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
            override fun selectAudioTrack(index: Int) = Unit
            override fun selectSubtitleTrack(index: Int) = Unit
            override fun setSubtitleUri(url: String) = Unit
            override fun clearExternalSubtitle() = Unit
            override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
        }
    }

    LaunchedEffect(sourceUrl, sourceAudioUrl, sourceHeaders) {
        latestOnControllerReady.value(controller)
        nativePlaybackAvailable.value = false
        val handedOff = playQtNativeMedia(
            sourceUrl,
            sourceHeaders.toHeadersJson(),
            0.0,
        )
        nativePlaybackAvailable.value = handedOff
        if (handedOff) {
            if (!playWhenReady) {
                commandQtNativePlayer("pause", 0.0)
            }
            latestOnSnapshot.value(
                PlayerPlaybackSnapshot(
                    isPlaying = playWhenReady,
                    isLoading = false,
                ),
            )
            latestOnError.value(null)
        } else {
            latestOnSnapshot.value(PlayerPlaybackSnapshot(isLoading = false))
            latestOnError.value("Playback is not available in this web runtime.")
        }
    }

    LaunchedEffect(playWhenReady) {
        if (!nativePlaybackAvailable.value) return@LaunchedEffect
        commandQtNativePlayer(if (playWhenReady) "resume" else "pause", 0.0)
        latestOnSnapshot.value(
            PlayerPlaybackSnapshot(
                isPlaying = playWhenReady,
                isLoading = false,
            ),
        )
    }

    DisposableEffect(sourceUrl) {
        onDispose {
            commandQtNativePlayer("stop", 0.0)
        }
    }

    Box(modifier = modifier)
}

private fun Map<String, String>.toHeadersJson(): String =
    entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${key.jsonEscaped()}\":\"${value.jsonEscaped()}\""
    }

private fun String.jsonEscaped(): String = buildString(length + 8) {
    this@jsonEscaped.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}
