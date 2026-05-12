package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MacOSMpvPlayerBackend : DesktopPlaybackBackend {
    @Composable
    override fun PlayerSurface(
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
        val bridge = remember { MacOSMPVBridgeLib.INSTANCE }
        val playerPtr = remember { bridge.nuvio_player_create() }
        var onCloseCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onAddonSubtitlesFetchCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onSourcesRequestedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onSourceStreamSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
        var onSourceFilterChangedCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }
        var onSourceReloadCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onEpisodesRequestedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onEpisodeSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
        var onEpisodeStreamSelectedCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
        var onEpisodeFilterChangedCallback by remember { mutableStateOf<((String?) -> Unit)?>(null) }
        var onEpisodeReloadCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onEpisodeBackCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
        var onNextEpisodeRequestedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

        DisposableEffect(playerPtr) {
            bridge.nuvio_player_show(playerPtr)
            onDispose {
                bridge.nuvio_player_destroy(playerPtr)
            }
        }

        LaunchedEffect(sourceUrl, sourceAudioUrl, sourceHeaders) {
            val headersJson = if (sourceHeaders.isNotEmpty()) {
                buildJsonObject {
                    sourceHeaders.forEach { (key, value) -> put(key, value) }
                }.toString()
            } else {
                null
            }
            bridge.nuvio_player_load_file(playerPtr, sourceUrl, sourceAudioUrl, headersJson)
            if (playWhenReady) {
                bridge.nuvio_player_play(playerPtr)
            }
        }

        LaunchedEffect(resizeMode) {
            val mode = when (resizeMode) {
                PlayerResizeMode.Fit -> 0
                PlayerResizeMode.Fill -> 1
                PlayerResizeMode.Zoom -> 2
            }
            bridge.nuvio_player_set_resize_mode(playerPtr, mode)
        }

        val controller = remember(playerPtr) {
            object : PlayerEngineController {
                override fun play() = bridge.nuvio_player_play(playerPtr)

                override fun pause() = bridge.nuvio_player_pause(playerPtr)

                override fun seekTo(positionMs: Long) = bridge.nuvio_player_seek_to(playerPtr, positionMs)

                override fun seekBy(offsetMs: Long) = bridge.nuvio_player_seek_by(playerPtr, offsetMs)

                override fun retry() = bridge.nuvio_player_retry(playerPtr)

                override fun setPlaybackSpeed(speed: Float) = bridge.nuvio_player_set_speed(playerPtr, speed)

                override fun getAudioTracks(): List<AudioTrack> {
                    val count = bridge.nuvio_player_get_audio_track_count(playerPtr)
                    return (0 until count).map { index ->
                        AudioTrack(
                            index = index,
                            id = bridge.nuvio_player_get_audio_track_id(playerPtr, index).toString(),
                            label = bridge.nuvio_player_get_audio_track_label(playerPtr, index) ?: "",
                            language = bridge.nuvio_player_get_audio_track_lang(playerPtr, index),
                            isSelected = bridge.nuvio_player_is_audio_track_selected(playerPtr, index),
                        )
                    }
                }

                override fun getSubtitleTracks(): List<SubtitleTrack> {
                    val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                    return (0 until count).map { index ->
                        SubtitleTrack(
                            index = index,
                            id = bridge.nuvio_player_get_subtitle_track_id(playerPtr, index).toString(),
                            label = bridge.nuvio_player_get_subtitle_track_label(playerPtr, index) ?: "",
                            language = bridge.nuvio_player_get_subtitle_track_lang(playerPtr, index),
                            isSelected = bridge.nuvio_player_is_subtitle_track_selected(playerPtr, index),
                        )
                    }
                }

                override fun selectAudioTrack(index: Int) {
                    val count = bridge.nuvio_player_get_audio_track_count(playerPtr)
                    if (index in 0 until count) {
                        val trackId = bridge.nuvio_player_get_audio_track_id(playerPtr, index)
                        bridge.nuvio_player_select_audio_track(playerPtr, trackId)
                    }
                }

                override fun selectSubtitleTrack(index: Int) {
                    if (index < 0) {
                        bridge.nuvio_player_select_subtitle_track(playerPtr, -1)
                        return
                    }
                    val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                    if (index in 0 until count) {
                        val trackId = bridge.nuvio_player_get_subtitle_track_id(playerPtr, index)
                        bridge.nuvio_player_select_subtitle_track(playerPtr, trackId)
                    }
                }

                override fun setSubtitleUri(url: String) {
                    bridge.nuvio_player_set_subtitle_url(playerPtr, url)
                }

                override fun clearExternalSubtitle() {
                    bridge.nuvio_player_clear_external_subtitle(playerPtr)
                }

                override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                    val trackId = if (trackIndex >= 0) {
                        val count = bridge.nuvio_player_get_subtitle_track_count(playerPtr)
                        if (trackIndex < count) bridge.nuvio_player_get_subtitle_track_id(playerPtr, trackIndex) else -1
                    } else {
                        -1
                    }
                    bridge.nuvio_player_clear_external_subtitle_and_select(playerPtr, trackId)
                }

                override fun applySubtitleStyle(style: SubtitleStyleState) {
                    val colorHex = style.textColor.toMpvColorString()
                    val outline = if (style.outlineEnabled) 2.0f else 0.0f
                    val subPos = 100 - style.bottomOffset
                    bridge.nuvio_player_apply_subtitle_style(
                        playerPtr,
                        colorHex,
                        outline,
                        style.fontSizeSp.toFloat(),
                        subPos,
                    )
                }

                override fun setMetadata(
                    title: String,
                    streamTitle: String,
                    providerName: String,
                    seasonNumber: Int?,
                    episodeNumber: Int?,
                    episodeTitle: String?,
                    artwork: String?,
                    logo: String?,
                ) {
                    bridge.nuvio_player_set_metadata(
                        playerPtr,
                        title,
                        streamTitle,
                        providerName,
                        seasonNumber ?: 0,
                        episodeNumber ?: 0,
                        episodeTitle,
                        artwork,
                        logo,
                    )
                }

                override fun setPlayerFlags(hasVideoId: Boolean, isSeries: Boolean) {
                    bridge.nuvio_player_set_has_video_id(playerPtr, hasVideoId)
                    bridge.nuvio_player_set_is_series(playerPtr, isSeries)
                }

                override fun showSkipButton(type: String, endTimeMs: Long) {
                    bridge.nuvio_player_show_skip_button(playerPtr, type, endTimeMs)
                }

                override fun hideSkipButton() {
                    bridge.nuvio_player_hide_skip_button(playerPtr)
                }

                override fun showNextEpisode(
                    season: Int,
                    episode: Int,
                    title: String,
                    thumbnail: String?,
                    hasAired: Boolean,
                ) {
                    bridge.nuvio_player_show_next_episode(playerPtr, season, episode, title, thumbnail, hasAired)
                }

                override fun hideNextEpisode() {
                    bridge.nuvio_player_hide_next_episode(playerPtr)
                }

                override fun setOnNextEpisodeRequestedCallback(callback: () -> Unit) {
                    onNextEpisodeRequestedCallback = callback
                }

                override fun setOnCloseCallback(callback: () -> Unit) {
                    onCloseCallback = callback
                }

                override fun setOnAddonSubtitlesFetchCallback(callback: () -> Unit) {
                    onAddonSubtitlesFetchCallback = callback
                }

                override fun pushAddonSubtitles(subtitles: List<AddonSubtitle>, isLoading: Boolean) {
                    bridge.nuvio_player_set_addon_subtitles_loading(playerPtr, isLoading)
                    if (!isLoading) {
                        bridge.nuvio_player_clear_addon_subtitles(playerPtr)
                        subtitles.forEach { addon ->
                            bridge.nuvio_player_add_addon_subtitle(
                                playerPtr,
                                addon.id,
                                addon.url,
                                addon.language,
                                addon.display,
                            )
                        }
                    }
                }

                override fun setOnSourcesRequestedCallback(callback: () -> Unit) {
                    onSourcesRequestedCallback = callback
                }

                override fun setOnSourceStreamSelectedCallback(callback: (String) -> Unit) {
                    onSourceStreamSelectedCallback = callback
                }

                override fun setOnSourceFilterChangedCallback(callback: (String?) -> Unit) {
                    onSourceFilterChangedCallback = callback
                }

                override fun setOnSourceReloadCallback(callback: () -> Unit) {
                    onSourceReloadCallback = callback
                }

                override fun setOnEpisodesRequestedCallback(callback: () -> Unit) {
                    onEpisodesRequestedCallback = callback
                }

                override fun setOnEpisodeSelectedCallback(callback: (String) -> Unit) {
                    onEpisodeSelectedCallback = callback
                }

                override fun setOnEpisodeStreamSelectedCallback(callback: (String) -> Unit) {
                    onEpisodeStreamSelectedCallback = callback
                }

                override fun setOnEpisodeFilterChangedCallback(callback: (String?) -> Unit) {
                    onEpisodeFilterChangedCallback = callback
                }

                override fun setOnEpisodeReloadCallback(callback: () -> Unit) {
                    onEpisodeReloadCallback = callback
                }

                override fun setOnEpisodeBackCallback(callback: () -> Unit) {
                    onEpisodeBackCallback = callback
                }

                override fun pushSourceData(
                    streams: List<StreamItem>,
                    groups: List<AddonStreamGroup>,
                    loading: Boolean,
                    selectedFilter: String?,
                    currentStreamUrl: String?,
                ) {
                    bridge.nuvio_player_set_sources_loading(playerPtr, loading)
                    bridge.nuvio_player_set_source_selected_filter(playerPtr, selectedFilter)
                    bridge.nuvio_player_clear_source_addon_groups(playerPtr)
                    groups.forEach { group ->
                        bridge.nuvio_player_add_source_addon_group(
                            playerPtr,
                            group.addonId,
                            group.addonName,
                            group.addonId,
                            group.isLoading,
                            group.error != null,
                        )
                    }
                    bridge.nuvio_player_clear_source_streams(playerPtr)
                    streams.forEach { stream ->
                        bridge.nuvio_player_add_source_stream(
                            playerPtr,
                            stream.addonId + "_" + (stream.url ?: stream.infoHash ?: ""),
                            stream.streamLabel,
                            stream.streamSubtitle,
                            stream.addonName,
                            stream.addonId,
                            stream.directPlaybackUrl ?: "",
                            stream.directPlaybackUrl == currentStreamUrl,
                        )
                    }
                }

                override fun pushEpisodes(episodes: List<MetaVideo>) {
                    bridge.nuvio_player_clear_episodes(playerPtr)
                    episodes.forEach { episode ->
                        bridge.nuvio_player_add_episode(
                            playerPtr,
                            episode.id,
                            episode.title,
                            episode.overview,
                            episode.thumbnail,
                            episode.season ?: 0,
                            episode.episode ?: 0,
                        )
                    }
                }

                override fun pushEpisodeStreamsData(
                    streams: List<StreamItem>,
                    groups: List<AddonStreamGroup>,
                    loading: Boolean,
                    selectedFilter: String?,
                    currentStreamUrl: String?,
                ) {
                    bridge.nuvio_player_set_episode_streams_loading(playerPtr, loading)
                    bridge.nuvio_player_set_episode_selected_filter(playerPtr, selectedFilter)
                    bridge.nuvio_player_clear_episode_addon_groups(playerPtr)
                    groups.forEach { group ->
                        bridge.nuvio_player_add_episode_addon_group(
                            playerPtr,
                            group.addonId,
                            group.addonName,
                            group.addonId,
                            group.isLoading,
                            group.error != null,
                        )
                    }
                    bridge.nuvio_player_clear_episode_streams(playerPtr)
                    streams.forEach { stream ->
                        bridge.nuvio_player_add_episode_stream(
                            playerPtr,
                            stream.addonId + "_" + (stream.url ?: stream.infoHash ?: ""),
                            stream.streamLabel,
                            stream.streamSubtitle,
                            stream.addonName,
                            stream.addonId,
                            stream.directPlaybackUrl ?: "",
                            stream.directPlaybackUrl == currentStreamUrl,
                        )
                    }
                }

                override fun showEpisodeStreamsView(season: Int?, episode: Int?, title: String?) {
                    bridge.nuvio_player_show_episode_streams(playerPtr, season ?: 0, episode ?: 0, title)
                }

                override fun switchSource(url: String, audioUrl: String?, headersJson: String?) {
                    bridge.nuvio_player_load_file(playerPtr, url, audioUrl, headersJson)
                }
            }
        }

        LaunchedEffect(controller) {
            onControllerReady(controller)
        }

        LaunchedEffect(playerPtr) {
            while (true) {
                delay(250)
                if (bridge.nuvio_player_is_closed(playerPtr)) {
                    onCloseCallback?.invoke()
                    break
                }
                bridge.nuvio_player_refresh_state(playerPtr)
                val snapshot = PlayerPlaybackSnapshot(
                    isLoading = bridge.nuvio_player_is_loading(playerPtr),
                    isPlaying = bridge.nuvio_player_is_playing(playerPtr),
                    isEnded = bridge.nuvio_player_is_ended(playerPtr),
                    positionMs = bridge.nuvio_player_get_position_ms(playerPtr),
                    durationMs = bridge.nuvio_player_get_duration_ms(playerPtr),
                    bufferedPositionMs = bridge.nuvio_player_get_buffered_ms(playerPtr),
                    playbackSpeed = bridge.nuvio_player_get_speed(playerPtr),
                )
                onSnapshot(snapshot)
                onError(bridge.nuvio_player_get_error(playerPtr))
                if (bridge.nuvio_player_is_addon_subtitles_fetch_requested(playerPtr)) {
                    onAddonSubtitlesFetchCallback?.invoke()
                }
                if (bridge.nuvio_player_pop_subtitle_style_changed(playerPtr)) {
                    val colorIndex = bridge.nuvio_player_get_subtitle_style_color_index(playerPtr)
                        .coerceIn(0, SubtitleColorSwatches.lastIndex)
                    val style = SubtitleStyleState(
                        textColor = SubtitleColorSwatches[colorIndex],
                        outlineEnabled = bridge.nuvio_player_get_subtitle_style_outline_enabled(playerPtr),
                        fontSizeSp = bridge.nuvio_player_get_subtitle_style_font_size(playerPtr),
                        bottomOffset = bridge.nuvio_player_get_subtitle_style_bottom_offset(playerPtr),
                    )
                    PlayerSettingsRepository.setSubtitleStyle(style)
                }
                if (bridge.nuvio_player_pop_next_episode_pressed(playerPtr)) {
                    onNextEpisodeRequestedCallback?.invoke()
                }
                if (bridge.nuvio_player_pop_sources_open_requested(playerPtr)) {
                    onSourcesRequestedCallback?.invoke()
                }
                if (bridge.nuvio_player_pop_episodes_open_requested(playerPtr)) {
                    onEpisodesRequestedCallback?.invoke()
                }
                bridge.nuvio_player_pop_source_stream_selected(playerPtr)?.let { url ->
                    onSourceStreamSelectedCallback?.invoke(url)
                }
                if (bridge.nuvio_player_pop_source_filter_changed(playerPtr)) {
                    onSourceFilterChangedCallback?.invoke(bridge.nuvio_player_get_source_filter_value(playerPtr))
                }
                if (bridge.nuvio_player_pop_source_reload(playerPtr)) {
                    onSourceReloadCallback?.invoke()
                }
                bridge.nuvio_player_pop_episode_selected(playerPtr)?.let { episodeId ->
                    onEpisodeSelectedCallback?.invoke(episodeId)
                }
                bridge.nuvio_player_pop_episode_stream_selected(playerPtr)?.let { url ->
                    onEpisodeStreamSelectedCallback?.invoke(url)
                }
                if (bridge.nuvio_player_pop_episode_filter_changed(playerPtr)) {
                    onEpisodeFilterChangedCallback?.invoke(bridge.nuvio_player_get_episode_filter_value(playerPtr))
                }
                if (bridge.nuvio_player_pop_episode_reload(playerPtr)) {
                    onEpisodeReloadCallback?.invoke()
                }
                if (bridge.nuvio_player_pop_episode_back(playerPtr)) {
                    onEpisodeBackCallback?.invoke()
                }
            }
        }

        Box(modifier = modifier.background(Color.Black))
    }
}

private fun Color.toMpvColorString(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return "#${r.hex()}${g.hex()}${b.hex()}${a.hex()}"
}

private fun Int.hex(): String = toString(16).padStart(2, '0').uppercase()
