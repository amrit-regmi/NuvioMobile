package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

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
    val controller = remember {
        object : PlayerEngineController {
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(positionMs: Long) = Unit
            override fun seekBy(offsetMs: Long) = Unit
            override fun retry() = Unit
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

    LaunchedEffect(sourceUrl, sourceAudioUrl) {
        latestOnControllerReady.value(controller)
        latestOnSnapshot.value(PlayerPlaybackSnapshot(isLoading = false))
        latestOnError.value("Playback is not available on web yet.")
    }

    Box(modifier = modifier)
}
