package com.nuvio.app.features.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.SpannableString
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.graphics.Typeface
import android.os.Build
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.util.AttributeSet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.CaptionStyleCompat
import com.nuvio.app.R
import com.nuvio.app.features.streams.normalizeStreamType
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "NuvioPlayer"

// r15: how long the FIRST prepare() will wait for async addon subtitle candidates to arrive so they
// can be bundled into the initial MediaItem (making the first subtitle switch pure track selection).
// Kept short so startup isn't delayed when no addon subtitles exist / they're slow; whatever cached
// candidates have arrived by the deadline are bundled, the rest fall back to the post-prepare fold.
private const val INITIAL_SUBTITLE_BUNDLE_WAIT_MS = 3_000L
private const val INITIAL_SUBTITLE_BUNDLE_POLL_MS = 50L

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val playerSettings = remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState.value
    }
    val playerSourceKey = listOf(
        sourceUrl,
        sourceAudioUrl.orEmpty(),
        sanitizePlaybackHeaders(sourceHeaders),
        sanitizePlaybackResponseHeaders(sourceResponseHeaders),
        normalizeStreamType(streamType).orEmpty(),
        useYoutubeChunkedPlayback,
    )
    var activeEngine by remember(playerSourceKey, playerSettings.androidPlaybackEngine) {
        mutableStateOf(playerSettings.androidPlaybackEngine.initialAndroidEngine())
    }

    when (activeEngine) {
        ResolvedAndroidPlaybackEngine.ExoPlayer -> ExoPlayerSurface(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = sourceHeaders,
            sourceResponseHeaders = sourceResponseHeaders,
            externalSubtitles = externalSubtitles,
            streamType = streamType,
            useYoutubeChunkedPlayback = useYoutubeChunkedPlayback,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            useNativeController = useNativeController,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = { message ->
                if (message != null && playerSettings.androidPlaybackEngine == AndroidPlaybackEngine.Auto) {
                    Log.w(TAG, "ExoPlayer failed; falling back to libmpv: $message")
                    activeEngine = ResolvedAndroidPlaybackEngine.Libmpv
                    onError(null)
                } else {
                    onError(message)
                }
            },
        )
        ResolvedAndroidPlaybackEngine.Libmpv -> LibmpvPlayerSurface(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            sourceHeaders = sourceHeaders,
            externalSubtitles = externalSubtitles,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            videoOutput = playerSettings.androidLibmpvVideoOutput,
            hardwareDecodingEnabled = playerSettings.androidLibmpvHardwareDecodingEnabled,
            yuv420pEnabled = playerSettings.androidLibmpvYuv420pEnabled,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
    }
}

private enum class ResolvedAndroidPlaybackEngine {
    ExoPlayer,
    Libmpv,
}

private fun AndroidPlaybackEngine.initialAndroidEngine(): ResolvedAndroidPlaybackEngine =
    when (this) {
        AndroidPlaybackEngine.Auto,
        AndroidPlaybackEngine.ExoPlayer -> ResolvedAndroidPlaybackEngine.ExoPlayer
        AndroidPlaybackEngine.Libmpv -> ResolvedAndroidPlaybackEngine.Libmpv
    }

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ExoPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val latestPlayWhenReady = rememberUpdatedState(playWhenReady)
    val coroutineScope = rememberCoroutineScope()

    val playerSettings = remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState.value
    }

    val sanitizedSourceHeaders = remember(sourceHeaders) {
        sanitizePlaybackHeaders(sourceHeaders)
    }
    val sanitizedSourceResponseHeaders = remember(sourceResponseHeaders) {
        sanitizePlaybackResponseHeaders(sourceResponseHeaders)
    }
    val normalizedStreamType = remember(streamType) {
        normalizeStreamType(streamType)
    }
    val useLibass = playerSettings.useLibass
    val libassRenderType = runCatching {
        LibassRenderType.valueOf(playerSettings.libassRenderType)
    }.getOrDefault(LibassRenderType.CUES)
    val playerSourceKey = listOf(
        sourceUrl,
        sourceAudioUrl.orEmpty(),
        sanitizedSourceHeaders,
        sanitizedSourceResponseHeaders,
        normalizedStreamType.orEmpty(),
        useYoutubeChunkedPlayback,
    )
    var subtitleDelayMs by remember(playerSourceKey) { mutableStateOf(0) }
    var selectedExternalSubtitleMimeType by remember(playerSourceKey) { mutableStateOf<String?>(null) }
    // Fix 1/2: external (addon) subtitle CANDIDATES (the prewarmed en/sv/fi set delivered by
    // setExternalSubtitles). These are folded into the media item EXACTLY ONCE — the moment they
    // first arrive — so the player has a single clean re-prepare (position preserved) and from then
    // on every auto-apply / switch is pure TRACK SELECTION (no prepare(), no video restart, no
    // repeated decoder init that exhausts codecs with `required system resources: 6`).
    // Keyed by source so it resets when the stream changes.
    var addonSubtitleCandidates by remember(playerSourceKey) {
        mutableStateOf<List<ExternalSubtitleInput>>(emptyList())
    }
    // URLs of external subtitles already attached to the media item as selectable text tracks.
    // Once a URL is here, switching to it is pure track selection (no prepare). Reset per source.
    val attachedExternalSubtitleUrls = remember(playerSourceKey) { mutableSetOf<String>() }
    val latestSubtitleDelayMs = rememberUpdatedState(subtitleDelayMs)
    val latestExternalSubtitleMimeType = rememberUpdatedState(selectedExternalSubtitleMimeType)
    var decoderPriorityOverride by remember(playerSourceKey) { mutableStateOf<Int?>(null) }
    var fallbackStartPositionMs by remember(playerSourceKey) { mutableStateOf<Long?>(null) }
    val effectiveDecoderPriority = decoderPriorityOverride ?: playerSettings.decoderPriority

    // Fix 1/2/3: bundle EVERY known external subtitle (stream-attached + prewarmed addon candidates)
    // into the media item with a stable id + clean human label up front, so the single prepare()
    // already contains the subtitle tracks. Keyed on the candidate set so that when the (async)
    // addon candidates first arrive, this recomputes ONCE and the prepare effect performs exactly
    // one clean re-prepare with the position preserved.
    val initialMediaItem = remember(playerSourceKey, externalSubtitles, addonSubtitleCandidates) {
        val subtitleConfigs = buildExternalSubtitleConfigurations(externalSubtitles, addonSubtitleCandidates)
        playbackMediaItemFromUrl(
            url = sourceUrl,
            responseHeaders = sanitizedSourceResponseHeaders,
            streamType = normalizedStreamType,
        ).buildUpon()
            .setMediaId(sourceUrl)
            .apply {
                if (subtitleConfigs.isNotEmpty()) {
                    setSubtitleConfigurations(subtitleConfigs)
                }
            }
            .build()
    }

    var resolvedMediaItem by remember(playerSourceKey) { mutableStateOf(initialMediaItem) }
    var probeAttempted by remember(playerSourceKey) { mutableStateOf(false) }
    // r15: latest initialMediaItem snapshot so the (briefly deferred) FIRST prepare can grab the
    // version that already bundles the async addon candidates, even though resolvedMediaItem still
    // points at the empty-candidate item captured at composition time.
    val latestInitialMediaItem = rememberUpdatedState(initialMediaItem)
    // r15: whether the single initial prepare() has happened yet. Until it has, the candidate-fold
    // effect must NOT re-prepare — instead the candidates are folded into the SAME initial prepare so
    // the FIRST subtitle switch (and the auto-apply at start) is pure track selection, zero restart.
    var initialPrepareDone by remember(playerSourceKey) { mutableStateOf(false) }
    // r15: has setExternalSubtitles delivered the addon candidate set yet? The initial prepare waits
    // briefly for this so the prewarmed en/sv/fi candidates ride the very first MediaItem.
    var addonCandidatesDelivered by remember(playerSourceKey) { mutableStateOf(false) }
    val latestAddonCandidatesDelivered = rememberUpdatedState(addonCandidatesDelivered)
    // r16: true while a position-preserving re-prepare (the late-subtitle fold) is in flight, i.e.
    // between calling prepare() and the next STATE_READY. The crash documented in
    // feedback_mobile_debug_discipline ("repeated prepares exhaust the video decoder → reclaim")
    // only happens when a SECOND re-prepare stacks on top of an in-flight one (two codec inits
    // racing). This latch makes the fold strictly serial: a new candidate batch arriving mid-fold
    // is deferred until the current re-prepare settles, so prepare() never overlaps itself.
    var reprepareInFlight by remember(playerSourceKey) { mutableStateOf(false) }
    // #87 / "wait 3s then option #2": late candidates (best subs that missed the initial-prepare
    // window) must NOT auto-fold the moment they ARRIVE — that surprise re-prepare was the "fetching
    // subtitles refreshes the stream" bug. Instead they sit unattached until the user (or auto-apply)
    // actually SELECTS one; only then do we perform a single position-preserving re-prepare to attach
    // it. setSubtitleUri sets this flag for an unbundled selection; the fold effect honors it.
    var foldRequested by remember(playerSourceKey) { mutableStateOf(false) }

    val extractorsFactory = remember {
        DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
    }
    val dataSourceFactory = remember(
        context,
        sanitizedSourceHeaders,
        sanitizedSourceResponseHeaders,
        useYoutubeChunkedPlayback,
        externalSubtitles,
    ) {
        PlatformPlaybackDataSourceFactory.create(
            context = context,
            defaultRequestHeaders = sanitizedSourceHeaders,
            defaultResponseHeaders = sanitizedSourceResponseHeaders,
            useYoutubeChunkedPlayback = useYoutubeChunkedPlayback,
            externalSubtitles = externalSubtitles,
        )
    }

    fun ExoPlayer.setPlaybackMediaItem(videoMediaItem: MediaItem, startPositionMs: Long? = null) {
        if (!sourceAudioUrl.isNullOrBlank()) {
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            val videoSource = mediaSourceFactory.createMediaSource(videoMediaItem)
            val audioSource = mediaSourceFactory.createMediaSource(playbackMediaItemFromUrl(sourceAudioUrl))
            val mergedSource = MergingMediaSource(videoSource, audioSource)
            if (startPositionMs != null) {
                setMediaSource(mergedSource, startPositionMs.coerceAtLeast(0L))
            } else {
                setMediaSource(mergedSource)
            }
        } else if (startPositionMs != null) {
            setMediaItem(videoMediaItem, startPositionMs.coerceAtLeast(0L))
        } else {
            setMediaItem(videoMediaItem)
        }
    }

    val exoPlayer = remember(
        sourceUrl,
        sourceAudioUrl,
        sanitizedSourceHeaders,
        sanitizedSourceResponseHeaders,
        normalizedStreamType,
        useYoutubeChunkedPlayback,
        effectiveDecoderPriority,
    ) {
        val renderersFactory = SubtitleOffsetRenderersFactory(
            context = context,
            subtitleDelayUsProvider = { latestSubtitleDelayMs.value.toLong() * 1_000L },
            shouldNormalizeCuePositionProvider = {
                latestExternalSubtitleMimeType.value == MimeTypes.TEXT_VTT
            },
        )
            .setExtensionRendererMode(effectiveDecoderPriority)
            .setEnableDecoderFallback(true)
            .setMapDV7ToHevc(playerSettings.mapDV7ToHevc)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
            )
            if (playerSettings.tunnelingEnabled) {
                setParameters(buildUponParameters().setTunnelingEnabled(true))
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setTargetBufferBytes(100 * 1024 * 1024)
            .setBufferDurationsMs(
                15_000,
                70_000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                5_000
            )
            .build()

        val player = if (useLibass) {
            ExoPlayer.Builder(context)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .buildWithAssSupportCompat(
                    context = context,
                    renderType = libassRenderType.toAssRenderType(),
                    dataSourceFactory = dataSourceFactory,
                    extractorsFactory = extractorsFactory,
                    renderersFactory = renderersFactory
                )
        } else {
            val mediaSourceFactory = DefaultMediaSourceFactory(
                dataSourceFactory,
                extractorsFactory,
            )

            ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
        }

        player
    }

    LaunchedEffect(exoPlayer, resolvedMediaItem) {
        if (!initialPrepareDone) {
            // r15: THE FIRST prepare. Briefly wait for the async addon subtitle candidates so they
            // ride this single MediaItem — then EVERY subtitle switch (including the first) and the
            // preferred-language auto-apply are pure track selection with no re-prepare. ExoPlayer
            // does not download a SubtitleConfiguration's body until its track is selected, so
            // bundling all (even 20-30) candidates here is cheap and must not slow startup; hence the
            // short, bounded ceiling. We poll the rememberUpdatedState snapshot so that whatever
            // candidates have been cached/delivered by the deadline are included.
            val deadline = System.currentTimeMillis() + INITIAL_SUBTITLE_BUNDLE_WAIT_MS
            while (
                !latestAddonCandidatesDelivered.value &&
                System.currentTimeMillis() < deadline &&
                isActive
            ) {
                delay(INITIAL_SUBTITLE_BUNDLE_POLL_MS)
            }
            val mediaItem = latestInitialMediaItem.value
            val configCount = mediaItem.localConfiguration?.subtitleConfigurations?.size ?: 0
            Log.d(
                TAG,
                "initial prepare(): bundling $configCount external subtitle config(s) " +
                    "(candidatesDelivered=${latestAddonCandidatesDelivered.value})",
            )
            // Mark every bundled subtitle url as attached so the fold effect is a no-op for them.
            attachedExternalSubtitleUrls.addAll(externalSubtitles.map { it.url })
            attachedExternalSubtitleUrls.addAll(addonSubtitleCandidates.map { it.url })
            exoPlayer.setPlaybackMediaItem(mediaItem, fallbackStartPositionMs)
            exoPlayer.prepare()
            initialPrepareDone = true
            return@LaunchedEffect
        }
        // Subsequent prepares (probe-MIME retry, decoder fallback) — these are genuine re-prepares,
        // not subtitle switches. The candidate-fold path no longer triggers this for the first switch.
        val mediaItem = resolvedMediaItem ?: return@LaunchedEffect
        exoPlayer.setPlaybackMediaItem(mediaItem, fallbackStartPositionMs)
        exoPlayer.prepare()
    }

    val pendingSubtitleTrackIndex = remember { mutableListOf<Int>() }
    // Fix 1/2: a preloaded external subtitle URI whose track hasn't surfaced yet; applied via track
    // selection (no prepare) the moment it appears in onTracksChanged.
    val pendingExternalSubtitleUri = remember { mutableListOf<String>() }
    val pendingAudioTrackSelection = remember { mutableListOf<TrackSelectionSnapshot>() }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var currentSubtitleStyle by remember { mutableStateOf(SubtitleStyleState.DEFAULT) }
    var subtitleSelectionJob by remember { mutableStateOf<Job?>(null) }

    fun syncPlayerViewKeepScreenOn() {
        playerViewRef?.keepScreenOn = exoPlayer.shouldKeepPlayerScreenOn()
    }

    fun preserveAudioSelectionForReload(reason: String) {
        pendingAudioTrackSelection.clear()
        val selection = exoPlayer.captureSelectedTrack(C.TRACK_TYPE_AUDIO) ?: return
        pendingAudioTrackSelection.add(selection)
        Log.d(TAG, "$reason: preserving audio track index=${selection.index} id=${selection.id}")
    }

    // r15: the addon subtitle candidates now ride the INITIAL MediaItem (the first prepare waits
    // briefly for them), so the FIRST switch / auto-apply is already pure track selection with ZERO
    // re-prepare. This effect is now ONLY a last-resort fallback: it fires when a candidate arrives
    // GENUINELY AFTER the initial prepare (e.g. it wasn't known/cached by the deadline) and is not
    // yet attached as a text track. In that rare case we fold it in with a single position-preserving
    // re-prepare. In the common case it is a complete no-op (every candidate already attached at
    // initial prepare), so the first subtitle switch never re-prepares.
    LaunchedEffect(exoPlayer, addonSubtitleCandidates, initialPrepareDone, reprepareInFlight, foldRequested) {
        if (!initialPrepareDone) return@LaunchedEffect
        // r16: never start a fold re-prepare while one is still settling — that stacked codec init is
        // the decoder-reclaim crash. When the in-flight re-prepare reaches STATE_READY the latch
        // clears, this effect re-runs (it's keyed on reprepareInFlight) and folds any candidates that
        // arrived in the meantime in a single, serial re-prepare.
        if (reprepareInFlight) return@LaunchedEffect
        // #87 / option #2: do NOT fold just because late candidates arrived (that surprise restart was
        // the reported bug). Only fold when a selection actually NEEDS an unbundled track — i.e.
        // setSubtitleUri raised foldRequested. Mere fetching/arrival leaves them unattached, no restart.
        if (!foldRequested) return@LaunchedEffect
        val candidates = addonSubtitleCandidates
        if (candidates.isEmpty()) { foldRequested = false; return@LaunchedEffect }
        if (candidates.all { attachedExternalSubtitleUrls.contains(it.url) }) { foldRequested = false; return@LaunchedEffect }
        attachedExternalSubtitleUrls.addAll(externalSubtitles.map { it.url })
        attachedExternalSubtitleUrls.addAll(candidates.map { it.url })
        val position = exoPlayer.currentPosition.coerceAtLeast(0L)
        fallbackStartPositionMs = position
        preserveAudioSelectionForReload("foldAddonSubtitleCandidates")
        Log.d(TAG, "foldAddonSubtitleCandidates (fallback, post-initial-prepare): bundling ${candidates.size} candidate(s), single re-prepare at position=$position")
        reprepareInFlight = true
        resolvedMediaItem = initialMediaItem
    }

    DisposableEffect(exoPlayer) {
        PlayerPictureInPictureManager.registerPausePlaybackCallback {
            exoPlayer.pause()
        }

        fun reportPlayerError(error: PlaybackException) {
            if (
                playerSettings.decoderPriority == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON &&
                effectiveDecoderPriority != DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER &&
                error.isDecoderFailure()
            ) {
                Log.w(
                    TAG,
                    "Decoder failure (${error.errorCodeName}); retrying with app decoders",
                    error,
                )
                fallbackStartPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                decoderPriorityOverride = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                latestOnError.value(null)
                return
            }
            latestOnError.value(error.localizedMessage ?: runBlocking { getString(Res.string.player_unable_to_play_stream) })
        }

        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                syncPlayerViewKeepScreenOn()

                val isSourceError = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                        error.cause?.toString()?.contains("UnrecognizedInputFormatException") == true

                if (isSourceError && !probeAttempted) {
                    probeAttempted = true
                    coroutineScope.launch {
                        val probedMime = withContext(Dispatchers.IO) {
                            probeMimeType(sourceUrl, sanitizedSourceHeaders)
                        }
                        if (probedMime != null) {
                            Log.d(TAG, "Playback failed with source error. Probed MIME type: $probedMime. Retrying...")
                            resolvedMediaItem = MediaItem.Builder()
                                .setUri(sourceUrl)
                                .setMimeType(probedMime)
                                .setMediaId(sourceUrl)
                                .apply {
                                    val subtitleConfigs = buildExternalSubtitleConfigurations(
                                        externalSubtitles,
                                        addonSubtitleCandidates,
                                    )
                                    if (subtitleConfigs.isNotEmpty()) {
                                        setSubtitleConfigurations(subtitleConfigs)
                                    }
                                }
                                .build()
                            latestOnError.value(null)
                            return@launch
                        }
                        reportPlayerError(error)
                    }
                    return
                }

                reportPlayerError(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                Log.d(TAG, "onPlaybackStateChanged: $stateName")
                if (playbackState == Player.STATE_READY) {
                    fallbackStartPositionMs = null
                    // r16: the (re-)prepare has settled — release the fold latch so any subtitle
                    // candidates that arrived mid-fold can now fold in a fresh serial re-prepare.
                    reprepareInFlight = false
                    latestOnError.value(null)
                    exoPlayer.logCurrentTracks("STATE_READY")
                }
                syncPlayerViewKeepScreenOn()
                latestOnSnapshot.value(exoPlayer.snapshot())
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncPlayerViewKeepScreenOn()
                latestOnSnapshot.value(exoPlayer.snapshot())
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                latestOnSnapshot.value(exoPlayer.snapshot())
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                Log.d(TAG, "onTracksChanged: ${tracks.groups.size} groups total")
                exoPlayer.logCurrentTracks("onTracksChanged")
                pendingAudioTrackSelection.firstOrNull()?.let { selection ->
                    if (tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                        pendingAudioTrackSelection.clear()
                        val restored = exoPlayer.restoreTrackSelection(selection)
                        Log.d(TAG, "onTracksChanged: restored pending audio selection=$restored")
                    }
                }
                if (pendingSubtitleTrackIndex.isNotEmpty() && tracks.groups.isNotEmpty()) {
                    val idx = pendingSubtitleTrackIndex.removeAt(0)
                    Log.d(TAG, "onTracksChanged: applying pending subtitle selection index=$idx")
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, idx < 0)
                        .build()
                    if (idx >= 0) {
                        exoPlayer.selectTrackByIndex(C.TRACK_TYPE_TEXT, idx)
                    }
                }
                // Fix 1/2: a preloaded external subtitle was requested before its track surfaced —
                // apply it now via track selection (no prepare()).
                if (pendingExternalSubtitleUri.isNotEmpty() && tracks.groups.isNotEmpty()) {
                    val url = pendingExternalSubtitleUri.first()
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build()
                    if (exoPlayer.selectExternalSubtitleByUri(url)) {
                        Log.d(TAG, "onTracksChanged: applied pending external subtitle via track selection url=$url")
                        pendingExternalSubtitleUri.removeAt(0)
                    }
                }
                latestOnSnapshot.value(exoPlayer.snapshot())
            }

        }
        exoPlayer.addListener(listener)
        onDispose {
            PlayerPictureInPictureManager.registerPausePlaybackCallback(null)
            exoPlayer.removeListener(listener)
            playerViewRef?.keepScreenOn = false
            subtitleSelectionJob?.cancel()
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val activity = context.findActivity()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> exoPlayer.playWhenReady = latestPlayWhenReady.value
                Lifecycle.Event.ON_STOP -> {
                    val isInPictureInPicture =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity?.isInPictureInPictureMode == true
                    val isFinishing = activity?.isFinishing == true
                    if (!isInPictureInPicture || isFinishing) {
                        exoPlayer.pause()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, playWhenReady) {
        exoPlayer.playWhenReady = latestPlayWhenReady.value
        syncPlayerViewKeepScreenOn()
        latestOnSnapshot.value(exoPlayer.snapshot())
    }

    LaunchedEffect(exoPlayer) {
        onControllerReady(
            object : PlayerEngineController {
                override fun play() {
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                }

                override fun pause() {
                    exoPlayer.pause()
                }

                override fun seekTo(positionMs: Long) {
                    exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
                }

                override fun seekBy(offsetMs: Long) {
                    exoPlayer.seekTo((exoPlayer.currentPosition + offsetMs).coerceAtLeast(0L))
                }

                override fun retry() {
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }

                override fun setPlaybackSpeed(speed: Float) {
                    exoPlayer.setPlaybackSpeed(speed)
                }

                override fun getAudioTracks(): List<AudioTrack> =
                    exoPlayer.extractAudioTracks(context)

                override fun getSubtitleTracks(): List<SubtitleTrack> {
                    val tracks = exoPlayer.extractSubtitleTracks(context)
                    Log.d(TAG, "getSubtitleTracks: found ${tracks.size} tracks")
                    tracks.forEach { t ->
                        Log.d(TAG, "  track idx=${t.index} id=${t.id} label='${t.label}' lang=${t.language} selected=${t.isSelected}")
                    }
                    return tracks
                }

                override fun selectAudioTrack(index: Int) {
                    exoPlayer.selectTrackByIndex(C.TRACK_TYPE_AUDIO, index)
                }

                override fun selectSubtitleTrack(index: Int) {
                    Log.d(TAG, "selectSubtitleTrack: index=$index")
                    if (index < 0) {
                        Log.d(TAG, "selectSubtitleTrack: disabling text tracks")
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                        return
                    }
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build()
                    exoPlayer.selectTrackByIndex(C.TRACK_TYPE_TEXT, index)
                    Log.d(TAG, "selectSubtitleTrack: after selection, textDisabled=${exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)}")
                    exoPlayer.logCurrentTracks("after selectSubtitleTrack")
                }

                override fun setExternalSubtitles(subtitles: List<ExternalSubtitleInput>) {
                    // r15: record the candidate set. If they arrive BEFORE the initial prepare (the
                    // common case — the prepare waits briefly for them), recomputing initialMediaItem
                    // bundles them straight into the FIRST MediaItem, so the first switch / auto-apply
                    // is pure track selection with ZERO re-prepare. If they somehow arrive after the
                    // initial prepare, the fallback fold effect performs a single position-preserving
                    // re-prepare. Either way every later switch is free track selection.
                    if (subtitles.isNotEmpty()) {
                        // Release the initial-prepare wait loop: candidates are now available to bundle.
                        addonCandidatesDelivered = true
                    }
                    if (addonSubtitleCandidates.map { it.url }.toSet() == subtitles.map { it.url }.toSet()) {
                        return
                    }
                    addonSubtitleCandidates = subtitles
                    Log.d(TAG, "setExternalSubtitles: ${subtitles.size} candidate(s) — bundling into initial media item (or folding if post-prepare)")
                }

                override fun setSubtitleUri(url: String, language: String, label: String) {
                    Log.d(TAG, "setSubtitleUri: url=$url language=$language label=$label")
                    selectedExternalSubtitleMimeType = guessSubtitleMime(url)
                    // Fix 1: subtitle selection is ALWAYS pure TRACK SELECTION. The candidate tracks
                    // were already merged into the media item by foldAddonSubtitleCandidates, so we
                    // never build a new MediaItem or call prepare() here — no STATE_READY cycle, no
                    // video restart, no decoder churn.
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                    if (exoPlayer.selectExternalSubtitleByUri(url)) {
                        Log.d(TAG, "setSubtitleUri: applied via TRACK SELECTION (no prepare), url=$url")
                        exoPlayer.logCurrentTracks("after setSubtitleUri (track-selection)")
                        return
                    }
                    // The track isn't present yet (candidates not folded / not yet surfaced). Ensure
                    // it's in the candidate set so the fold effect picks it up, then queue selection
                    // for onTracksChanged. Still no manual prepare() here.
                    Log.d(TAG, "setSubtitleUri: track not surfaced yet; ensuring candidate + queuing for onTracksChanged")
                    if (addonSubtitleCandidates.none { it.url == url }) {
                        // Fix 2: synthesize the candidate with the CLEAN language/label passed by the
                        // caller (the chosen AddonSubtitle) so the folded track has a real language for
                        // preferred-language matching and a human label — never the raw URL/".zip".
                        addonSubtitleCandidates = addonSubtitleCandidates +
                            ExternalSubtitleInput(
                                url = url,
                                language = language.cleanSubtitleLanguageOrNull() ?: "",
                                label = cleanSubtitleLabel(rawLabel = label, language = language, url = url),
                            )
                    }
                    // #87 / option #2: this is an EXPLICIT selection of a not-yet-bundled track, so
                    // authorize the single position-preserving fold (the only re-prepare we allow,
                    // and only here — never on mere fetch/arrival).
                    foldRequested = true
                    pendingExternalSubtitleUri.clear()
                    pendingExternalSubtitleUri.add(url)
                }

                override fun clearExternalSubtitle() {
                    Log.d(TAG, "clearExternalSubtitle called")
                    subtitleSelectionJob?.cancel()
                    pendingExternalSubtitleUri.clear()
                    selectedExternalSubtitleMimeType = null
                    // Fix 1: preloaded external subtitle tracks stay loaded — just DISABLE text via
                    // track selection. No new MediaItem, no prepare(), no restart.
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    Log.d(TAG, "clearExternalSubtitle: text disabled via track selection (no prepare)")
                }

                override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                    Log.d(TAG, "clearExternalSubtitleAndSelect: trackIndex=$trackIndex")
                    subtitleSelectionJob?.cancel()
                    pendingExternalSubtitleUri.clear()
                    selectedExternalSubtitleMimeType = null
                    // Fix 1: switch from an external sub to a built-in/embedded text track purely via
                    // track selection. The preloaded external configs remain available.
                    if (trackIndex < 0) {
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    } else {
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                        exoPlayer.selectTrackByIndex(C.TRACK_TYPE_TEXT, trackIndex)
                    }
                    Log.d(TAG, "clearExternalSubtitleAndSelect: applied via track selection index=$trackIndex (no prepare)")
                }

                override fun applySubtitleStyle(style: SubtitleStyleState) {
                    currentSubtitleStyle = style
                    playerViewRef?.applySubtitleStyle(style)
                }

                override fun setSubtitleDelayMs(delayMs: Int) {
                    subtitleDelayMs = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
                }
            }
        )
    }

    LaunchedEffect(exoPlayer) {
        while (isActive) {
            latestOnSnapshot.value(exoPlayer.snapshot())
            delay(250L)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = useNativeController
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                player = exoPlayer
                keepScreenOn = exoPlayer.shouldKeepPlayerScreenOn()
                this.resizeMode = resizeMode.toExoResizeMode()
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                playerViewRef = this
                syncLibassOverlay(
                    player = exoPlayer,
                    enabled = useLibass,
                    renderType = libassRenderType,
                )
                applySubtitleStyle(currentSubtitleStyle)
            }
        },
        update = { playerView ->
            playerView.player = exoPlayer
            playerView.useController = useNativeController
            playerView.resizeMode = resizeMode.toExoResizeMode()
            playerViewRef = playerView
            syncPlayerViewKeepScreenOn()
            playerView.syncLibassOverlay(
                player = exoPlayer,
                enabled = useLibass,
                renderType = libassRenderType,
            )
            playerView.applySubtitleStyle(currentSubtitleStyle)
        },
    )
}

@Composable
private fun LibmpvPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    videoOutput: AndroidLibmpvVideoOutput,
    hardwareDecodingEnabled: Boolean,
    yuv420pEnabled: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val latestPlayWhenReady = rememberUpdatedState(playWhenReady)
    val coroutineScope = rememberCoroutineScope()
    val sanitizedSourceHeaders = remember(sourceHeaders) {
        sanitizePlaybackHeaders(sourceHeaders)
    }
    var playerViewRef by remember { mutableStateOf<NuvioLibmpvView?>(null) }

    DisposableEffect(lifecycleOwner) {
        val activity = context.findActivity()
        val observer = LifecycleEventObserver { _, event ->
            val view = playerViewRef ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.setPaused(!latestPlayWhenReady.value)
                Lifecycle.Event.ON_STOP -> {
                    val isInPictureInPicture =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity?.isInPictureInPictureMode == true
                    val isFinishing = activity?.isFinishing == true
                    if (!isInPictureInPicture || isFinishing) {
                        view.setPaused(true)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(playerViewRef) {
        val view = playerViewRef ?: return@DisposableEffect onDispose {}
        fun dispatchSnapshot(updateKeepScreenOn: Boolean = false) {
            coroutineScope.launch(Dispatchers.Main.immediate) {
                latestOnSnapshot.value(view.snapshot())
                if (updateKeepScreenOn) {
                    view.keepScreenOn = view.shouldKeepScreenOn()
                }
            }
        }
        val observer = object : MPV.EventObserver {
            override fun eventProperty(property: String) = Unit
            override fun eventProperty(property: String, value: Long) {
                if (property == "cache-buffering-state") {
                    dispatchSnapshot(updateKeepScreenOn = true)
                }
            }
            override fun eventProperty(property: String, value: Boolean) {
                if (property == "eof-reached" || property == "pause" || property == "paused-for-cache" || property == "seeking") {
                    dispatchSnapshot(updateKeepScreenOn = true)
                }
            }
            override fun eventProperty(property: String, value: String) = Unit
            override fun eventProperty(property: String, value: Double) {
                if (property == "duration" || property == "time-pos" || property == "speed") {
                    dispatchSnapshot()
                }
            }
            override fun eventProperty(property: String, value: MPVNode) {
                if (property == "track-list") dispatchSnapshot()
            }
            override fun event(eventId: Int, data: MPVNode) {
                when (eventId) {
                    MPV.mpvEvent.MPV_EVENT_START_FILE -> {
                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            latestOnError.value(null)
                            latestOnSnapshot.value(PlayerPlaybackSnapshot())
                        }
                    }
                    MPV.mpvEvent.MPV_EVENT_FILE_LOADED,
                    MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            latestOnError.value(null)
                            latestOnSnapshot.value(view.snapshot())
                        }
                    }
                    MPV.mpvEvent.MPV_EVENT_END_FILE -> dispatchSnapshot()
                }
            }
        }
        view.mpv.addObserver(observer)
        onDispose {
            view.mpv.removeObserver(observer)
        }
    }

    DisposableEffect(playerViewRef) {
        val view = playerViewRef ?: return@DisposableEffect onDispose {}
        PlayerPictureInPictureManager.registerPausePlaybackCallback {
            view.setPaused(true)
        }
        onDispose {
            PlayerPictureInPictureManager.registerPausePlaybackCallback(null)
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(playerViewRef, sourceUrl, sourceAudioUrl, sanitizedSourceHeaders, externalSubtitles) {
        val view = playerViewRef ?: return@LaunchedEffect
        latestOnSnapshot.value(PlayerPlaybackSnapshot())
        view.loadSource(
            sourceUrl = sourceUrl,
            sourceAudioUrl = sourceAudioUrl,
            requestHeaders = sanitizedSourceHeaders,
            externalSubtitles = externalSubtitles,
            playWhenReady = latestPlayWhenReady.value,
        )
    }

    LaunchedEffect(playerViewRef, playWhenReady) {
        val view = playerViewRef ?: return@LaunchedEffect
        view.setPaused(!latestPlayWhenReady.value)
        view.keepScreenOn = view.shouldKeepScreenOn()
        latestOnSnapshot.value(view.snapshot())
    }

    LaunchedEffect(playerViewRef, resizeMode) {
        playerViewRef?.applyResizeMode(resizeMode)
    }

    LaunchedEffect(playerViewRef, sourceUrl, sourceAudioUrl, sanitizedSourceHeaders, externalSubtitles) {
        val view = playerViewRef ?: return@LaunchedEffect
        onControllerReady(view.controller(context))
    }

    LaunchedEffect(playerViewRef) {
        val view = playerViewRef ?: return@LaunchedEffect
        while (isActive) {
            latestOnSnapshot.value(view.snapshot())
            view.keepScreenOn = view.shouldKeepScreenOn()
            delay(250L)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            NuvioLibmpvView(
                context = viewContext,
                videoOutput = videoOutput,
                hardwareDecodingEnabled = hardwareDecodingEnabled,
                yuv420pEnabled = yuv420pEnabled,
            ).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                keepScreenOn = false
                runCatching {
                    Utils.copyAssets(viewContext)
                    initialize(viewContext.filesDir.path, viewContext.cacheDir.path)
                }.onFailure { error ->
                    Log.e(TAG, "Failed to initialize libmpv", error)
                    latestOnError.value(error.localizedMessage ?: "libmpv unavailable")
                }
                playerViewRef = this
            }
        },
        update = { view ->
            playerViewRef = view
            view.applyResizeMode(resizeMode)
        },
        onRelease = { view ->
            if (playerViewRef === view) playerViewRef = null
            runCatching { view.destroy() }
        },
    )
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private class NuvioLibmpvView(
    context: Context,
    private val videoOutput: AndroidLibmpvVideoOutput,
    private val hardwareDecodingEnabled: Boolean,
    private val yuv420pEnabled: Boolean,
    attrs: AttributeSet? = null,
) : BaseMPVView(context, attrs) {
    private var currentSourceUrl: String? = null
    private var currentSourceAudioUrl: String? = null
    private var currentRequestHeaders: Map<String, String> = emptyMap()
    private var currentExternalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList()

    override fun initOptions() {
        setVo(videoOutput.mpvValue)
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("hwdec", if (hardwareDecodingEnabled) "auto" else "no")
        if (yuv420pEnabled) {
            mpv.setOptionString("vf", "format=yuv420p")
        }
        mpv.setOptionString("msg-level", "all=warn")
        mpv.setOptionString("tls-verify", "yes")
        mpv.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")
        mpv.setOptionString("demuxer-max-bytes", "${libmpvCacheBytes()}").logIfMpvError("demuxer-max-bytes")
        mpv.setOptionString("demuxer-max-back-bytes", "${libmpvCacheBytes()}").logIfMpvError("demuxer-max-back-bytes")
        mpv.setOptionString("vd-lavc-film-grain", "cpu")
        mpv.setPropertyBoolean("keep-open", true)
        mpv.setPropertyBoolean("input-default-bindings", true)
        mpv.setPropertyBoolean("audio-fallback-to-null", true)
    }

    override fun postInitOptions() = Unit

    override fun observeProperties() {
        val props = mapOf(
            "pause" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "core-idle" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "seeking" to MPV.mpvFormat.MPV_FORMAT_FLAG,
            "cache-buffering-state" to MPV.mpvFormat.MPV_FORMAT_INT64,
            "duration" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "time-pos" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "demuxer-cache-time" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "speed" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
            "track-list" to MPV.mpvFormat.MPV_FORMAT_NODE,
        )
        props.forEach { (name, format) -> mpv.observeProperty(name, format) }
    }

    fun loadSource(
        sourceUrl: String,
        sourceAudioUrl: String?,
        requestHeaders: Map<String, String>,
        externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
        playWhenReady: Boolean,
    ) {
        val sameSource =
            currentSourceUrl == sourceUrl &&
                currentSourceAudioUrl == sourceAudioUrl &&
                currentRequestHeaders == requestHeaders &&
                currentExternalSubtitles == externalSubtitles
        currentSourceUrl = sourceUrl
        currentSourceAudioUrl = sourceAudioUrl
        currentRequestHeaders = requestHeaders
        currentExternalSubtitles = externalSubtitles
        if (!sameSource) {
            loadCurrentSource(playWhenReady = playWhenReady)
        } else {
            applyRequestHeaders(requestHeaders)
            setPaused(!playWhenReady)
        }
    }

    private fun loadCurrentSource(playWhenReady: Boolean) {
        val sourceUrl = currentSourceUrl ?: return
        applyRequestHeaders(currentRequestHeaders)
        setPaused(!playWhenReady)
        mpv.command("loadfile", sourceUrl, "replace")
        currentSourceAudioUrl?.takeIf { it.isNotBlank() }?.let { sourceAudioUrl ->
            mpv.command("audio-add", sourceAudioUrl, "auto")
        }
        currentExternalSubtitles.forEachIndexed { index, subtitle ->
            val flag = if (index == 0) "auto" else "cached"
            mpv.command("sub-add", subtitle.url, flag)
        }
        setPaused(!playWhenReady)
    }

    fun setPaused(paused: Boolean) {
        runCatching { mpv.setPropertyBoolean("pause", paused) }
    }

    fun snapshot(): PlayerPlaybackSnapshot {
        val paused = mpv.getPropertyBoolean("pause") ?: true
        val pausedForCache = mpv.getPropertyBoolean("paused-for-cache") ?: false
        val idle = mpv.getPropertyBoolean("core-idle") ?: false
        val ended = mpv.getPropertyBoolean("eof-reached") ?: false
        val seeking = mpv.getPropertyBoolean("seeking") ?: false
        val cacheBufferingState = mpv.getPropertyInt("cache-buffering-state")
        val durationMs = mpv.getPropertyDouble("duration").toMillis()
        val positionMs = mpv.getPropertyDouble("time-pos").toMillis()
        val cachePositionMs = mpv.getPropertyDouble("demuxer-cache-time").toMillis()
        val isCacheBuffering = cacheBufferingState != null && cacheBufferingState in 0 until 100
        val isLoading = pausedForCache ||
            (!paused && !ended && (seeking || isCacheBuffering || (idle && durationMs <= 0L)))
        return PlayerPlaybackSnapshot(
            isLoading = isLoading,
            isPlaying = !paused && !isLoading && !idle && !ended,
            isEnded = ended,
            durationMs = durationMs,
            positionMs = positionMs,
            bufferedPositionMs = maxOf(positionMs, cachePositionMs),
            playbackSpeed = (mpv.getPropertyDouble("speed") ?: 1.0).toFloat(),
        )
    }

    fun shouldKeepScreenOn(): Boolean {
        val snapshot = snapshot()
        return snapshot.isPlaying || snapshot.isLoading
    }

    fun applyResizeMode(resizeMode: PlayerResizeMode) {
        when (resizeMode) {
            PlayerResizeMode.Fit -> {
                mpv.setPropertyDouble("panscan", 0.0)
                mpv.setPropertyString("video-aspect-override", "no")
            }
            PlayerResizeMode.Fill -> {
                mpv.setPropertyDouble("panscan", 1.0)
                mpv.setPropertyString("video-aspect-override", "no")
            }
            PlayerResizeMode.Zoom -> {
                mpv.setPropertyDouble("panscan", 0.5)
                mpv.setPropertyString("video-aspect-override", "no")
            }
        }
    }

    fun controller(context: Context): PlayerEngineController =
        object : PlayerEngineController {
            override fun play() = setPaused(false)

            override fun pause() = setPaused(true)

            override fun seekTo(positionMs: Long) {
                mpv.command("seek", (positionMs.coerceAtLeast(0L) / 1000.0).toString(), "absolute")
            }

            override fun seekBy(offsetMs: Long) {
                mpv.command("seek", (offsetMs / 1000.0).toString(), "relative")
            }

            override fun retry() {
                loadCurrentSource(playWhenReady = true)
            }

            override fun setPlaybackSpeed(speed: Float) {
                mpv.setPropertyDouble("speed", speed.coerceIn(0.25f, 4f).toDouble())
            }

            override fun setMuted(muted: Boolean) {
                mpv.setPropertyBoolean("mute", muted)
            }

            override fun getAudioTracks(): List<AudioTrack> =
                extractLibmpvTracks(context, type = "audio").mapIndexed { index, track ->
                    AudioTrack(
                        index = index,
                        id = track.id.toString(),
                        label = track.label,
                        language = track.language,
                        isSelected = track.isSelected,
                    )
                }

            override fun getSubtitleTracks(): List<SubtitleTrack> =
                extractLibmpvTracks(context, type = "sub").mapIndexed { index, track ->
                    SubtitleTrack(
                        index = index,
                        id = track.id.toString(),
                        label = track.label,
                        language = track.language,
                        isSelected = track.isSelected,
                        isForced = track.isForced,
                    )
                }

            override fun selectAudioTrack(index: Int) {
                if (index < 0) {
                    mpv.setPropertyString("aid", "no")
                } else {
                    extractLibmpvTracks(context, type = "audio").getOrNull(index)?.let { track ->
                        mpv.setPropertyInt("aid", track.id)
                    }
                }
            }

            override fun selectSubtitleTrack(index: Int) {
                if (index < 0) {
                    mpv.setPropertyString("sid", "no")
                } else {
                    extractLibmpvTracks(context, type = "sub").getOrNull(index)?.let { track ->
                        mpv.setPropertyInt("sid", track.id)
                    }
                }
            }

            override fun setSubtitleUri(url: String, language: String, label: String) {
                mpv.command("sub-add", url, "select")
            }

            override fun clearExternalSubtitle() {
                mpv.setPropertyString("sid", "no")
            }

            override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
                selectSubtitleTrack(trackIndex)
            }

            override fun applySubtitleStyle(style: SubtitleStyleState) {
                mpv.setPropertyString("sub-ass-override", "no")
                mpv.setPropertyString("sub-color", style.textColor.toMpvColor())
                mpv.setPropertyString("sub-back-color", style.backgroundColor.toMpvColor())
                mpv.setPropertyString("sub-outline-color", style.outlineColor.toMpvColor())
                mpv.setPropertyString("sub-border-color", style.outlineColor.toMpvColor())
                mpv.setPropertyString("sub-border-style", style.toMpvSubtitleBorderStyle())
                mpv.setPropertyString("sub-bold", if (style.bold) "yes" else "no")
                mpv.setPropertyInt("sub-font-size", style.toMpvSubtitleFontSize())
                mpv.setPropertyInt("sub-outline-size", style.toMpvSubtitleOutlineSize())
                mpv.setPropertyInt("sub-border-size", style.toMpvSubtitleOutlineSize())
                mpv.setPropertyInt("sub-pos", (100 - style.bottomOffset / 10).coerceIn(0, 100))
            }

            override fun setSubtitleDelayMs(delayMs: Int) {
                mpv.setPropertyDouble(
                    "sub-delay",
                    delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS) / 1000.0,
                )
            }
        }

    private fun applyRequestHeaders(headers: Map<String, String>) {
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        if (!userAgent.isNullOrBlank()) {
            mpv.setPropertyString("user-agent", userAgent)
        }
        val serialized = headers
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .map { (key, value) -> "${key}: ${value.replace(",", "\\,")}" }
            .joinToString(",")
        mpv.setPropertyString("http-header-fields", serialized)
    }

    private fun extractLibmpvTracks(context: Context, type: String): List<LibmpvTrack> {
        val nodes = mpv.getPropertyNode("track-list")?.asArray()?.toList().orEmpty()
        return nodes
            .filter { node -> node.nodeString("type") == type }
            .mapIndexedNotNull { index, node ->
                val id = node.nodeInt("id") ?: return@mapIndexedNotNull null
                val rawLabel = node.nodeString("title")
                    ?: node.nodeString("external-filename")?.substringAfterLast('/')
                    ?: node.nodeString("codec")
                val language = node.nodeString("lang") ?: normalizeLanguageCode(rawLabel)
                val label = rawLabel?.takeIf { it.isNotBlank() }
                    ?: runBlocking { getString(Res.string.compose_player_track_number, index + 1) }
                LibmpvTrack(
                    id = id,
                    label = label,
                    language = language,
                    isSelected = node.nodeBoolean("selected") ?: false,
                    isForced = inferForcedSubtitleTrack(
                        label = label,
                        language = language,
                        trackId = id.toString(),
                        hasForcedSelectionFlag = node.nodeBoolean("forced") ?: false,
                    ),
                )
            }
    }
}

private data class LibmpvTrack(
    val id: Int,
    val label: String,
    val language: String?,
    val isSelected: Boolean,
    val isForced: Boolean,
)

private fun libmpvCacheBytes(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 * 1024 * 1024 else 32 * 1024 * 1024

private fun Int.logIfMpvError(option: String) {
    if (this < 0) Log.w(TAG, "libmpv option failed: $option status=$this")
}

private fun Double?.toMillis(): Long =
    this?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000.0).toLong() } ?: 0L

private fun MPVNode.nodeString(key: String): String? =
    runCatching { this[key]?.asString() }.getOrNull()?.takeIf { it.isNotBlank() }

private fun MPVNode.nodeInt(key: String): Int? =
    runCatching { this[key]?.asInt()?.toInt() }.getOrNull()

private fun MPVNode.nodeBoolean(key: String): Boolean? =
    runCatching { this[key]?.asBoolean() }.getOrNull()

private fun androidx.compose.ui.graphics.Color.toMpvColor(): String {
    val argb = toArgb()
    val alpha = (argb ushr 24) and 0xff
    val red = (argb shr 16) and 0xff
    val green = (argb shr 8) and 0xff
    val blue = argb and 0xff
    return "#%02X%02X%02X%02X".format(alpha, red, green, blue)
}

private fun androidx.compose.ui.graphics.Color.alphaByte(): Int =
    (toArgb() ushr 24) and 0xff

private fun SubtitleStyleState.toMpvSubtitleFontSize(): Int =
    (fontSizeSp * MPV_SUBTITLE_FONT_SIZE_SCALE).toInt().coerceIn(
        MPV_SUBTITLE_FONT_SIZE_MIN,
        MPV_SUBTITLE_FONT_SIZE_MAX,
    )

private fun SubtitleStyleState.toMpvSubtitleOutlineSize(): Int =
    if (!outlineEnabled) 0 else (outlineWidth * MPV_SUBTITLE_OUTLINE_SIZE_SCALE).toInt().coerceAtLeast(1)

private fun SubtitleStyleState.toMpvSubtitleBorderStyle(): String =
    if (outlineEnabled) {
        "outline-and-shadow"
    } else if (backgroundColor.alphaByte() > 0) {
        "opaque-box"
    } else {
        "outline-and-shadow"
    }

private const val MPV_SUBTITLE_FONT_SIZE_SCALE = 55.0 / 18.0
private const val MPV_SUBTITLE_FONT_SIZE_MIN = 36
private const val MPV_SUBTITLE_FONT_SIZE_MAX = 122
private const val MPV_SUBTITLE_OUTLINE_SIZE_SCALE = 1.5

private fun ExoPlayer.snapshot(): PlayerPlaybackSnapshot =
    PlayerPlaybackSnapshot(
        isLoading = playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING,
        isPlaying = isPlaying,
        isEnded = playbackState == Player.STATE_ENDED,
        durationMs = duration.coerceAtLeast(0L),
        positionMs = currentPosition.coerceAtLeast(0L),
        bufferedPositionMs = bufferedPosition.coerceAtLeast(0L),
        playbackSpeed = playbackParameters.speed,
    )

private fun ExoPlayer.shouldKeepPlayerScreenOn(): Boolean =
    playerError == null &&
        playWhenReady &&
        playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY)

private data class TrackSelectionSnapshot(
    val trackType: Int,
    val index: Int,
    val id: String?,
    val language: String?,
    val label: String?,
    val sampleMimeType: String?,
    val codecs: String?,
    val channelCount: Int,
    val roleFlags: Int,
)

private fun ExoPlayer.captureSelectedTrack(trackType: Int): TrackSelectionSnapshot? {
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != trackType) continue
        if (group.isSelected) {
            val format = group.mediaTrackGroup.getFormat(0)
            return TrackSelectionSnapshot(
                trackType = trackType,
                index = idx,
                id = format.id,
                language = format.language,
                label = format.label,
                sampleMimeType = format.sampleMimeType,
                codecs = format.codecs,
                channelCount = format.channelCount,
                roleFlags = format.roleFlags,
            )
        }
        idx++
    }
    return null
}

private fun ExoPlayer.restoreTrackSelection(selection: TrackSelectionSnapshot): Boolean {
    selection.id?.takeIf { it.isNotBlank() }?.let { id ->
        val restored = selectTrackByPredicate(selection.trackType, "id=$id") { _, format ->
            format.id == id
        }
        if (restored) {
            return true
        }
    }

    selection.label?.takeIf { it.isNotBlank() }?.let { label ->
        val restored = selectTrackByPredicate(selection.trackType, "label=$label") { _, format ->
            format.label.equals(label, ignoreCase = true) &&
                (selection.language.isNullOrBlank() ||
                    format.language.equals(selection.language, ignoreCase = true))
        }
        if (restored) {
            return true
        }
    }

    val technicalMatchIndexes = mutableListOf<Int>()
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != selection.trackType) continue
        val format = group.mediaTrackGroup.getFormat(0)
        if (
            !selection.language.isNullOrBlank() &&
            format.language.equals(selection.language, ignoreCase = true) &&
            format.sampleMimeType == selection.sampleMimeType &&
            format.codecs == selection.codecs &&
            format.channelCount == selection.channelCount &&
            format.roleFlags == selection.roleFlags
        ) {
            technicalMatchIndexes.add(idx)
        }
        idx++
    }
    if (technicalMatchIndexes.size == 1) {
        return selectTrackByIndex(selection.trackType, technicalMatchIndexes.first())
    }

    return selectTrackByIndex(selection.trackType, selection.index)
}

private fun PlaybackException.isDecoderFailure(): Boolean =
    errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    )

private fun PlayerResizeMode.toExoResizeMode(): Int =
    when (this) {
        PlayerResizeMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        PlayerResizeMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        PlayerResizeMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

private fun PlayerView.syncLibassOverlay(
    player: ExoPlayer,
    enabled: Boolean,
    renderType: LibassRenderType,
) {
    val containerId = if (renderType == LibassRenderType.OVERLAY_OPEN_GL) {
        R.id.libass_overlay_container_gl
    } else {
        R.id.libass_overlay_container
    }
    val overlayContainer = findViewById<android.widget.FrameLayout>(containerId) ?: return
    val needsOverlay = enabled && renderType.usesOverlaySubtitleView()
    val boundPlayer = getTag(R.id.libass_overlay_bound_player) as? ExoPlayer
    val hasOverlayChild = overlayContainer.hasAssOverlayChild()

    if (!needsOverlay) {
        if (hasOverlayChild) {
            overlayContainer.removeAssOverlayChildren()
        }
        if (boundPlayer != null) {
            setTag(R.id.libass_overlay_bound_player, null)
        }
        return
    }

    val assHandler = player.getAssHandlerCompat() ?: return
    if (boundPlayer === player && hasOverlayChild) {
        return
    }

    overlayContainer.removeAssOverlayChildren()
    val assSubtitleView = AssSubtitleView(overlayContainer.context, assHandler)
    overlayContainer.addView(
        assSubtitleView,
        android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    )
    setTag(R.id.libass_overlay_bound_player, player)
}

private fun LibassRenderType.usesOverlaySubtitleView(): Boolean =
    this == LibassRenderType.OVERLAY_CANVAS || this == LibassRenderType.OVERLAY_OPEN_GL

private fun android.widget.FrameLayout.hasAssOverlayChild(): Boolean {
    for (index in 0 until childCount) {
        if (getChildAt(index) is AssSubtitleView) {
            return true
        }
    }
    return false
}

private fun android.widget.FrameLayout.removeAssOverlayChildren() {
    for (index in childCount - 1 downTo 0) {
        if (getChildAt(index) is AssSubtitleView) {
            removeViewAt(index)
        }
    }
}

private fun PlayerView.applySubtitleStyle(style: SubtitleStyleState) {
    subtitleView?.apply {
        val baseBottomPaddingFraction = SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION * 2f / 3f
        val offsetFraction = (style.bottomOffset / 1000f).coerceIn(0f, 0.2f)
        val bottomPaddingFraction = (baseBottomPaddingFraction + offsetFraction).coerceIn(0f, 0.4f)

        setApplyEmbeddedStyles(false)
        setApplyEmbeddedFontSizes(false)
        setBottomPaddingFraction(bottomPaddingFraction)
        setStyle(
            CaptionStyleCompat(
                style.textColor.toArgb(),
                style.backgroundColor.toArgb(),
                android.graphics.Color.TRANSPARENT,
                if (style.outlineEnabled) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
                style.outlineColor.toArgb(),
                if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT,
            )
        )
        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp.toFloat())
    }
}

private fun ExoPlayer.extractAudioTracks(context: Context): List<AudioTrack> {
    val tracks = mutableListOf<AudioTrack>()
    val trackNameProvider = CustomDefaultTrackNameProvider(context.resources)
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        val format = group.mediaTrackGroup.getFormat(0)
        val label = trackNameProvider.getTrackName(format).takeIf { it.isNotBlank() }
            ?: runBlocking { getString(Res.string.compose_player_track_number, idx + 1) }
        tracks.add(
            AudioTrack(
                index = idx,
                id = format.id ?: idx.toString(),
                label = label,
                language = format.language,
                isSelected = group.isSelected,
            )
        )
        idx++
    }
    return tracks
}

/**
 * Fix 1/2: a stable subtitle-track id derived from the external subtitle URL. media3 surfaces this
 * as `Format.id` for the sideloaded text track, letting us match-and-select an already-preloaded
 * subtitle purely via track selection (no re-prepare / no video restart).
 */
internal fun externalSubtitleTrackId(url: String): String = "ext-sub:$url"

/** Stable prefix for the id of a side-loaded external subtitle track (see [externalSubtitleTrackId]). */
private const val EXTERNAL_SUBTITLE_TRACK_ID_PREFIX = "ext-sub:"

/**
 * r16: true when a text track's Format.id identifies a side-loaded external/catalog subtitle (as
 * opposed to a muxed/embedded track). ExoPlayer rewrites the SubtitleConfiguration id to
 * "<sourceIndex>:ext-sub:<url>" when merging via MergingMediaSource, so match both the raw prefix and
 * the index-prefixed form. Used to keep these out of the "Built-in" tab (they live in "Addons").
 */
internal fun String.isExternalSubtitleTrackId(): Boolean =
    startsWith(EXTERNAL_SUBTITLE_TRACK_ID_PREFIX) || contains(":$EXTERNAL_SUBTITLE_TRACK_ID_PREFIX")

/**
 * Build a [MediaItem.SubtitleConfiguration] for a preloaded external subtitle. Sets a stable id and
 * a clean label so the picker never shows the raw ".zip" URL filename (Fix 3) and selection can
 * match by id (Fix 1).
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun ExternalSubtitleInput.toSubtitleConfiguration(): MediaItem.SubtitleConfiguration {
    val mimeType = resolveSubtitleMimeType(url)
    val language = language.cleanSubtitleLanguageOrNull()
    return MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
        .setId(externalSubtitleTrackId(url))
        .setMimeType(mimeType)
        .apply { language?.let { setLanguage(it) } }
        .setLabel(cleanSubtitleLabel(rawLabel = label, language = language, url = url))
        .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
        .build()
}

/**
 * Fix 1/2/3: build subtitle configurations for EVERY known external subtitle — the stream-attached
 * ones plus the prewarmed addon candidates — so they can all be bundled into the media item at
 * build time (single prepare) with clean human labels and stable ids (for track-selection switching
 * that never re-prepares). De-duplicated by url; the addon candidate (which carries a clean display
 * label + language) wins over a bare stream subtitle for the same url.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun buildExternalSubtitleConfigurations(
    streamSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    addonCandidates: List<ExternalSubtitleInput>,
): List<MediaItem.SubtitleConfiguration> {
    val configs = LinkedHashMap<String, MediaItem.SubtitleConfiguration>()
    streamSubtitles.forEach { subtitle ->
        val mimeType = resolveSubtitleMimeType(subtitle.url, subtitle.headers)
        val language = subtitle.language.cleanSubtitleLanguageOrNull()
        configs[subtitle.url] = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
            .setId(externalSubtitleTrackId(subtitle.url))
            .setMimeType(mimeType)
            .apply { language?.let { setLanguage(it) } }
            .setLabel(cleanSubtitleLabel(rawLabel = subtitle.name, language = language, url = subtitle.url))
            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .build()
    }
    addonCandidates.forEach { candidate ->
        configs[candidate.url] = candidate.toSubtitleConfiguration()
    }
    return configs.values.toList()
}

/**
 * Fix 2/3: never let a raw URL / ".zip" filename masquerade as a language. Returns null when the
 * candidate clearly is a URL/path/archive so callers don't tag the track with garbage that would
 * also break preferred-language auto-select.
 */
private fun String?.cleanSubtitleLanguageOrNull(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val looksLikeUrlOrFile = value.contains("://") ||
        value.contains('/') ||
        value.endsWith(".zip", ignoreCase = true) ||
        value.endsWith(".srt", ignoreCase = true) ||
        value.endsWith(".vtt", ignoreCase = true) ||
        value.endsWith(".ass", ignoreCase = true) ||
        value.endsWith(".ssa", ignoreCase = true)
    return if (looksLikeUrlOrFile) null else value
}

/**
 * Fix 3: derive a clean, human subtitle track label. Prefers an explicit non-URL label, then the
 * language's display name, finally a generic "Subtitle" — but NEVER the raw download URL / ".zip"
 * filename that the backend's download-proxy URL would otherwise leak into the picker.
 */
private fun cleanSubtitleLabel(rawLabel: String?, language: String?, url: String): String {
    rawLabel?.cleanSubtitleLanguageOrNull()?.let { return it }
    language?.let { code ->
        subtitleLanguageDisplayName(code)?.let { return it }
        return code
    }
    return "Subtitle"
}

/** Minimal ISO-639 → display-name map covering the prewarmed languages (en/sv/fi) and common ones. */
private fun subtitleLanguageDisplayName(code: String): String? {
    val normalized = code.trim().lowercase().substringBefore('-').substringBefore('_')
    return when (normalized) {
        "en", "eng", "english" -> "English"
        "sv", "swe", "swedish" -> "Svenska"
        "fi", "fin", "finnish" -> "Suomi"
        "es", "spa", "spanish" -> "Español"
        "fr", "fre", "fra", "french" -> "Français"
        "de", "ger", "deu", "german" -> "Deutsch"
        "it", "ita", "italian" -> "Italiano"
        "pt", "por", "portuguese" -> "Português"
        "nl", "dut", "nld", "dutch" -> "Nederlands"
        "da", "dan", "danish" -> "Dansk"
        "no", "nor", "norwegian" -> "Norsk"
        "ru", "rus", "russian" -> "Русский"
        "ar", "ara", "arabic" -> "العربية"
        "zh", "chi", "zho", "chinese" -> "中文"
        "ja", "jpn", "japanese" -> "日本語"
        "ko", "kor", "korean" -> "한국어"
        "hi", "hin", "hindi" -> "हिन्दी"
        "pl", "pol", "polish" -> "Polski"
        "tr", "tur", "turkish" -> "Türkçe"
        else -> null
    }
}

/**
 * Select an already-preloaded external subtitle track by its URL via TRACK SELECTION only.
 * Returns true if a matching loaded text track was found and selected (no prepare()); false if the
 * track isn't present yet (caller should fall back to the rebuild+prepare path).
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun ExoPlayer.selectExternalSubtitleByUri(url: String): Boolean {
    val targetId = externalSubtitleTrackId(url)
    return selectTrackByPredicate(C.TRACK_TYPE_TEXT, "extUri=$url") { _, format ->
        // ExoPlayer rewrites a side-loaded SubtitleConfiguration's id to "<sourceIndex>:<originalId>"
        // when it merges the external subtitle into the MergingMediaSource (observed as
        // "1:ext-sub:<url>"). Match the original id OR that index-prefixed form — exact equality
        // alone never matched, which is why selection always fell through to the fold/re-prepare path.
        val fid = format.id
        fid == targetId || fid?.endsWith(":$targetId") == true
    }
}

private fun ExoPlayer.extractSubtitleTracks(context: Context): List<SubtitleTrack> {
    val tracks = mutableListOf<SubtitleTrack>()
    val trackNameProvider = CustomDefaultTrackNameProvider(context.resources)
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_TEXT) continue
        val format = group.mediaTrackGroup.getFormat(0)
        // r16: side-loaded external / catalog (HamroCinema) subtitles are bundled into the MediaItem
        // as SubtitleConfigurations and surface here as text tracks indistinguishable from muxed ones,
        // which made them leak into the "Built-in" tab. They already have their own "Addons" tab entry
        // (addonSubtitles), so EXCLUDE them from the Built-in list. The Built-in list must contain ONLY
        // tracks muxed into the video. External subs carry the stable id "ext-sub:<url>" (ExoPlayer may
        // index-prefix it to "<n>:ext-sub:<url>" when merged) — match either form.
        if (format.id?.isExternalSubtitleTrackId() == true) {
            continue
        }
        val hasForcedSelectionFlag = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
        // Fix 3: prefer the clean label we attached to the SubtitleConfiguration. Fall back to the
        // track-name provider, but if that still produced a URL/".zip" (e.g. an embedded track with
        // a filename name), derive a clean language name so the picker never shows the raw URL.
        val providerLabel = trackNameProvider.getTrackName(format)
        val cleanLabel = format.label?.cleanSubtitleLanguageOrNull()
            ?: providerLabel.cleanSubtitleLanguageOrNull()
            ?: cleanSubtitleLabel(rawLabel = null, language = format.language, url = format.id ?: "")
        tracks.add(
            SubtitleTrack(
                index = idx,
                id = format.id ?: idx.toString(),
                label = cleanLabel,
                language = format.language,
                isSelected = group.isSelected,
                isForced = inferForcedSubtitleTrack(
                    label = format.label,
                    language = format.language,
                    trackId = format.id,
                    hasForcedSelectionFlag = hasForcedSelectionFlag,
                ),
            )
        )
        idx++
    }
    return tracks
}

private fun ExoPlayer.selectTrackByIndex(trackType: Int, targetIndex: Int): Boolean {
    return selectTrackByPredicate(trackType, "index=$targetIndex") { idx, _ ->
        idx == targetIndex
    }
}

private fun ExoPlayer.selectTrackByPredicate(
    trackType: Int,
    targetDescription: String,
    predicate: (index: Int, format: Format) -> Boolean,
): Boolean {
    val typeName = if (trackType == C.TRACK_TYPE_AUDIO) "AUDIO" else "TEXT"
    Log.d(TAG, "selectTrack: type=$typeName target=$targetDescription")
    var idx = 0
    for (group in currentTracks.groups) {
        if (group.type != trackType) continue
        val format = group.mediaTrackGroup.getFormat(0)
        if (!predicate(idx, format)) {
            idx++
            continue
        }
        Log.d(TAG, "selectTrack: found group at idx=$idx, format.id=${format.id}, lang=${format.language}, label=${format.label}")
        trackSelectionParameters = trackSelectionParameters
            .buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(0))
            )
            .build()
        Log.d(TAG, "selectTrack: override applied")
        return true
    }
    Log.w(TAG, "selectTrack: no group found for type=$typeName target=$targetDescription (total groups scanned=$idx)")
    return false
}

private fun ExoPlayer.logCurrentTracks(context: String) {
    Log.d(TAG, "--- logCurrentTracks ($context) ---")
    Log.d(TAG, "  textDisabled=${trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)}")
    for (group in currentTracks.groups) {
        val typeName = when (group.type) {
            C.TRACK_TYPE_AUDIO -> "AUDIO"
            C.TRACK_TYPE_TEXT -> "TEXT"
            C.TRACK_TYPE_VIDEO -> "VIDEO"
            else -> "OTHER(${group.type})"
        }
        if (group.type != C.TRACK_TYPE_TEXT && group.type != C.TRACK_TYPE_AUDIO) continue
        val format = group.mediaTrackGroup.getFormat(0)
        Log.d(TAG, "  group type=$typeName id=${format.id} lang=${format.language} label=${format.label} selected=${group.isSelected} supported=${group.isSupported}")
    }
    Log.d(TAG, "--- end logCurrentTracks ---")
}

@androidx.annotation.OptIn(UnstableApi::class)
private class SubtitleOffsetRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val normalizingOutput = CueNormalizingTextOutput(
            delegate = output,
            shouldNormalizeCuePositionProvider = shouldNormalizeCuePositionProvider,
        )
        val startIndex = out.size
        super.buildTextRenderers(context, normalizingOutput, outputLooper, extensionRendererMode, out)
        for (index in startIndex until out.size) {
            out[index] = SubtitleOffsetRenderer(
                baseRenderer = out[index],
                subtitleDelayUsProvider = subtitleDelayUsProvider,
            )
        }
    }
}

private class CueNormalizingTextOutput(
    private val delegate: TextOutput,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
        val processed = cueGroup.cues.map(::processCue)
        delegate.onCues(CueGroup(processed, cueGroup.presentationTimeUs))
    }

    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        delegate.onCues(cues.map(::processCue))
    }

    private fun processCue(cue: Cue): Cue {
        var processed = fixRtlCueText(cue)
        if (shouldNormalizeCuePositionProvider()) {
            processed = normalizeCuePosition(processed)
        }
        return processed
    }

    private fun normalizeCuePosition(cue: Cue): Cue {
        if (cue.bitmap != null || cue.verticalType != Cue.TYPE_UNSET || cue.line == Cue.DIMEN_UNSET) {
            return cue
        }
        return cue.buildUpon()
            .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
            .setLineAnchor(Cue.TYPE_UNSET)
            .build()
    }

    private fun fixRtlCueText(cue: Cue): Cue {
        val text = cue.text ?: return cue
        if (!containsRtlChars(text)) return cue
        val original = text.toString()
        val fixed = original.split('\n').joinToString("\n") { line ->
            moveLeadingRtlPunctuationToEnd(line)
        }
        if (fixed == original) return cue
        return cue.buildUpon().setText(SpannableString(fixed)).build()
    }

    private fun moveLeadingRtlPunctuationToEnd(line: String): String {
        if (line.isEmpty()) return line
        var end = 0
        while (end < line.length && line[end] in RTL_PUNCTUATION) end++
        if (end == 0) return line
        return line.substring(end) + line.substring(0, end)
    }

    private fun containsRtlChars(text: CharSequence): Boolean {
        for (char in text) {
            val directionality = Character.getDirectionality(char)
            if (
                directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            ) {
                return true
            }
        }
        return false
    }

    companion object {
        private val RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(')
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class SubtitleOffsetRenderer(
    baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long,
) : ForwardingRenderer(baseRenderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val adjustedPositionUs = (positionUs - subtitleDelayUsProvider()).coerceAtLeast(0L)
        super.render(adjustedPositionUs, elapsedRealtimeUs)
    }
}

private fun resolveSubtitleMimeType(url: String, headers: Map<String, String>? = null): String {
    probeSubtitleHeaders(url, headers)?.let { (contentType, contentDisposition) ->
        mapSubtitleMime(contentType)?.let { return it }
        filenameFromContentDisposition(contentDisposition)?.let(::guessSubtitleMime)?.let { return it }
    }
    return guessSubtitleMime(url)
}

private fun probeSubtitleHeaders(url: String, headers: Map<String, String>? = null): Pair<String?, String?>? {
    val methods = listOf("HEAD", "GET")
    methods.forEach { method ->
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "*/*")
                headers?.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }
            try {
                connection.responseCode
                connection.contentType to connection.getHeaderField("Content-Disposition")
            } finally {
                connection.disconnect()
            }
        }.getOrNull()?.let { return it }
    }
    return null
}

private fun mapSubtitleMime(contentType: String?): String? {
    val normalized = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?: return null

    return when (normalized) {
        "application/x-subrip",
        "application/srt",
        "text/srt",
        "text/plain" -> MimeTypes.APPLICATION_SUBRIP
        "text/vtt",
        "application/vtt" -> MimeTypes.TEXT_VTT
        "text/x-ssa",
        "text/ssa",
        "text/ass",
        "application/x-ssa" -> MimeTypes.TEXT_SSA
        "application/ttml+xml",
        "text/xml",
        "application/xml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }
}

private fun filenameFromContentDisposition(contentDisposition: String?): String? =
    contentDisposition
        ?.substringAfter("filename=", missingDelimiterValue = "")
        ?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotEmpty() }

private fun guessSubtitleMime(url: String): String {
    val lower = url.lowercase()
    return when {
        lower.contains(".srt") -> MimeTypes.APPLICATION_SUBRIP
        lower.contains(".vtt") || lower.contains(".webvtt") -> MimeTypes.TEXT_VTT
        lower.contains(".ass") || lower.contains(".ssa") -> MimeTypes.TEXT_SSA
        lower.contains(".ttml") || lower.contains(".dfxp") || lower.contains(".xml") -> MimeTypes.APPLICATION_TTML
        // Our backend's subtitle download-proxy serves SRT via an extension-less URL
        // (/catalog-addon/subtitles/download?u=...). Defaulting to WebVTT made ExoPlayer's
        // WebvttParser throw "Expected WEBVTT" on SRT bodies, disabling the track / breaking
        // playback. SRT is the de-facto default our backend serves, so fall back to it.
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

internal class SubtitleRequestHeaderDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        SubtitleRequestHeaderDataSource(
            upstream = upstreamFactory.createDataSource(),
            externalSubtitles = externalSubtitles,
        )
}

internal class SubtitleRequestHeaderDataSource(
    private val upstream: DataSource,
    private val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val subtitle = externalSubtitles.find { it.url == url }
        val headers = subtitle?.headers
        
        return if (headers.isNullOrEmpty()) {
            upstream.open(dataSpec)
        } else {
            val mergedHeaders = dataSpec.httpRequestHeaders.toMutableMap()
            headers.forEach { (key, value) ->
                mergedHeaders[key] = value
            }
            upstream.open(dataSpec.buildUpon().setHttpRequestHeaders(mergedHeaders).build())
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }
}
