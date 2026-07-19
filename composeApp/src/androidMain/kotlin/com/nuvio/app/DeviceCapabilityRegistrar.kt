package com.nuvio.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.device.DeviceCapabilities
import com.nuvio.app.core.device.DeviceCapabilitiesSnapshot
import com.nuvio.app.core.network.BackendAuth
import com.nuvio.app.core.network.PrivateBackend
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

object DeviceCapabilityRegistrar {
    private val log = Logger.withTag("DeviceCapabilityRegistrar")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun registerAsync(context: Context) {
        // Publish the device id synchronously so stream requests can carry `?profile=<id>` from the
        // very first navigation, even before the async PUT below lands.
        runCatching { PrivateBackend.deviceProfileId = deriveDeviceId(context) }
        scope.launch {
            try {
                register(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "Device capability registration failed" }
            }
        }
    }

    private suspend fun register(context: Context) {
        // Wait up to 10s for auth to settle (handles cold start where session is still loading).
        val authState = try {
            withTimeout(10_000L) {
                AuthRepository.state.filterIsInstance<AuthState.Authenticated>().first()
            }
        } catch (_: TimeoutCancellationException) { return }
        if (authState.isAnonymous) return

        val userId = authState.userId
        val deviceId = deriveDeviceId(context)
        val decode = detectDecodeCaps()
        val maxResolution = detectMaxResolution(decode)
        val hdrTypes = detectHdrTypes(context, decode)
        val codecs = detectCodecs()
        val audio = detectAudioCaps(context)
        val formFactor = detectFormFactor(context)
        val appVersion = detectAppVersion(context)
        // Bound stream size so the backend never returns a file this device can't comfortably
        // stream. 2160p is capped well below full-remux size on purpose: a device with a sub-4K
        // panel (e.g. a tablet) benefits from a proper 4K *encode* (~4-20GB) downscaled to its
        // panel, but a 40-80GB 4K REMUX would buffer on mobile single-connection playback (see the
        // far-CDN/single-conn buffering history) and offers no visible gain on a 1080p-class panel.
        val maxSizeGb = when (maxResolution) {
            "2160p" -> 25
            "1080p" -> 15
            else -> 5
        }
        // Report the active network's estimated downstream bandwidth so the backend can right-size
        // the stream to the link, not just the decoder. 0 when unknown (no permission needed).
        val downloadSpeedMbps = detectDownstreamMbps(context)

        // Publish the SAME detected caps into the shared commonMain holder so the stream-picker
        // badge UI can compute client-side downgrade pills (a 4K/DV/Atmos badge on a device that
        // tops out lower) without another detection pass. Display-only; backend still does the
        // real device-cap stream filtering via `?profile=`.
        publishSharedCapabilities(maxResolution, hdrTypes, audio)

        val body = buildString {
            append("{")
            append("\"device_id\":\"$deviceId\",")
            append("\"user_id\":\"$userId\",")
            append("\"device_name\":\"${Build.MODEL}\",")
            append("\"form_factor\":\"$formFactor\",")
            append("\"app_version\":\"$appVersion\",")
            append("\"max_resolution\":\"$maxResolution\",")
            append("\"hdr_types_supported\":${hdrTypes.joinToString(",", "[", "]") { "\"$it\"" }},")
            append("\"max_audio_channels\":\"${audio.maxChannelsLabel}\",")
            append("\"preferred_audio_formats\":${audio.formats.joinToString(",", "[", "]") { "\"$it\"" }},")
            append("\"supported_codecs\":${codecs.joinToString(",", "[", "]") { "\"$it\"" }},")
            append("\"max_size_gb\":$maxSizeGb,")
            append("\"download_speed_mbps\":$downloadSpeedMbps")
            append("}")
        }

        val url = "${PrivateBackend.baseUrl}/catalog-addon/device-profile"
        val headers = BackendAuth.authHeadersFor(url) + mapOf("Content-Type" to "application/json")
        val response = httpRequestRaw("PUT", url, headers, body)
        log.d {
            "Device capability registration: ${response.status} | id=$deviceId res=$maxResolution " +
                "hdr=$hdrTypes codecs=$codecs audio=${audio.maxChannelsLabel}/${audio.formats} " +
                "maxSizeGb=$maxSizeGb downMbps=$downloadSpeedMbps form=$formFactor " +
                "decode(maxH=${decode.maxHeight} hdr10=${decode.hevcHdr10} 10bit=${decode.hevc10bit} dv=${decode.dolbyVision})"
        }
    }

    /**
     * Report the Android form factor to the backend (`form_factor` field on the device profile) so a
     * tablet is no longer misclassified as a TV. A LEANBACK / TV UI mode device is "tv"; otherwise we
     * use the smallest-width breakpoint (>=600dp → "tablet", else "phone"). This is the NuvioMobile
     * build, so on a tablet it reports "tablet".
     */
    private fun detectFormFactor(context: Context): String {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        val isTelevision = uiModeManager?.currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        if (isTelevision) return "tv"
        val smallestWidthDp = context.resources.configuration.smallestScreenWidthDp
        return if (smallestWidthDp >= 600) "tablet" else "phone"
    }

    /**
     * Read the installed app's versionName via PackageManager (robust across modules; avoids a
     * cross-module BuildConfig import). Reported to the backend as `app_version`.
     */
    private fun detectAppVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    /**
     * Estimated downstream bandwidth (Mbps) of the active network, read from
     * [NetworkCapabilities.getLinkDownstreamBandwidthKbps]. This is the OS link estimate (no probe
     * download, no extra permission). Returns 0 when the network/estimate is unavailable.
     */
    private fun detectDownstreamMbps(context: Context): Int {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return 0
            val network = cm.activeNetwork ?: return 0
            val caps: NetworkCapabilities = cm.getNetworkCapabilities(network) ?: return 0
            (caps.linkDownstreamBandwidthKbps / 1000).coerceAtLeast(0)
        } catch (_: Exception) {
            0
        }
    }

    private fun deriveDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        return UUID.nameUUIDFromBytes("$androidId:${Build.MODEL}".toByteArray(Charsets.UTF_8)).toString()
    }

    /**
     * What the device can actually DECODE (not what the panel advertises). The panel may report a
     * 2160p mode and HDR10 support while the HEVC decoder can't render a 4K HDR10 Main10 remux —
     * which black-screens. We therefore cap reported capability by the hardware decoders' real
     * limits: the max decodable video height, and whether HEVC exposes a 10-bit / HDR10 profile.
     */
    private data class DecodeCaps(
        val maxHeight: Int,
        val hevcHdr10: Boolean,
        val hevc10bit: Boolean,
        val dolbyVision: Boolean,
    )

    private fun detectDecodeCaps(): DecodeCaps {
        var maxHeight = 0
        var hevcHdr10 = false
        var hevc10 = false
        var dv = false
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    val t = type.lowercase()
                    val isHevc = "hevc" in t || "h265" in t
                    val isAvc = "avc" in t || "h264" in t
                    val isAv1 = "av01" in t || "av1" in t
                    if ("dolby-vision" in t || "dolbyvision" in t) { dv = true; continue }
                    if (!isHevc && !isAvc && !isAv1) continue
                    val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
                    val vc = caps.videoCapabilities
                    if (vc != null) {
                        val h = runCatching { vc.supportedHeights.upper }.getOrNull() ?: 0
                        if (h > maxHeight) maxHeight = h
                    }
                    if (isHevc) {
                        for (pl in caps.profileLevels) {
                            when (pl.profile) {
                                android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ->
                                    hevc10 = true
                                android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                                android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> {
                                    hevc10 = true; hevcHdr10 = true
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return DecodeCaps(maxHeight, hevcHdr10, hevc10, dv)
    }

    private fun detectMaxResolution(decode: DecodeCaps): String {
        // Cap by what the hardware can DECODE, NOT by the panel resolution. A device with a sub-4K
        // panel (e.g. a Galaxy Tab S6 Lite: ~1200p panel, but its decoder handles 4K HEVC) can still
        // decode a 2160p source and downscale it — and a high-bitrate 4K encode downscaled to the
        // panel looks better than a native 1080p one. The decoder limit is the real black-screen
        // guard (a source the decoder can't handle); the panel is not. HDR is still gated separately
        // by decode caps in detectHdrTypes, and stream size is bounded by maxSizeGb above. Never
        // report below 1080p (avoids under-serving when a decoder probe comes back unexpectedly low).
        val decodeHeight = if (decode.maxHeight > 0) decode.maxHeight else 1080
        val effective = maxOf(decodeHeight, 1080)
        return when {
            effective >= 2160 -> "2160p"
            effective >= 1080 -> "1080p"
            else -> "720p"
        }
    }

    private fun detectHdrTypes(context: Context, decode: DecodeCaps): List<String> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return emptyList()
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return emptyList()
        val hdr = display.hdrCapabilities ?: return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        val types = hdr.supportedHdrTypes
        return buildList {
            // Only claim an HDR format when BOTH the panel advertises it AND a decoder can actually
            // produce it — otherwise the backend serves an HDR remux that black-screens on decode.
            if (android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 in types && decode.hevcHdr10) add("HDR10")
            if (android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types && decode.dolbyVision) add("DolbyVision")
            if (android.view.Display.HdrCapabilities.HDR_TYPE_HLG in types && decode.hevc10bit) add("HLG")
            if (android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types && decode.hevcHdr10) add("HDR10+")
        }
    }

    private fun detectCodecs(): List<String> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val names = list.codecInfos.filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }.toSet()
        return buildList {
            if (names.any { "hevc" in it || "h265" in it }) add("H.265")
            if (names.any { "av01" in it || "av1" in it }) add("AV1")
            add("H.264")
        }
    }

    private data class AudioCaps(
        val maxChannelsLabel: String,
        val formats: List<String>,
    )

    /**
     * What the device can actually RENDER for audio — detected, never hardcoded — so a future device
     * (a phone with only stereo, a tablet on a 5.1 soundbar, a TV passing Atmos/DTS:X through HDMI to
     * an AVR) each report their real capability. Two independent signals are unioned:
     *
     *  1. **Decoders** — enumerate `MediaCodecList` audio decoders. Each surround/object codec maps to
     *     a format label the backend understands: ac3→Dolby Digital, eac3→Dolby Digital Plus,
     *     eac3-joc/ac4→Dolby Atmos, true-hd→Dolby TrueHD, dts→DTS, dts-hd→DTS-HD, dts-uhd→DTS:X. The
     *     decoders' `maxInputChannelCount` gives the channels the device can decode on-board.
     *
     *  2. **HDMI passthrough** — a device wired to an AVR/soundbar may not decode Atmos/DTS:X itself
     *     but can BITSTREAM it downstream. We probe `AudioTrack.isDirectPlaybackSupported` (API 29+)
     *     for E-AC3-JOC / AC4 / DTS / DTS-HD, and read HDMI/HDMI-ARC sink channel masks via
     *     `AudioManager.getDevices()` for the real output channel count. Union of both wins.
     *
     * AAC is always included (universally decodable). Channel label is derived from the highest
     * channel count seen across decoders and HDMI sinks (8→7.1, 6→5.1, else 2.0). All API-gated
     * constants are guarded by SDK_INT so this compiles/runs from API 21 up.
     */
    private fun detectAudioCaps(context: Context): AudioCaps {
        val formats = linkedSetOf("AAC")
        var maxChannels = 2

        // (1) On-board decoders.
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    val t = type.lowercase()
                    if (!t.startsWith("audio/")) continue
                    when {
                        "eac3-joc" in t -> { formats += "Dolby Digital Plus"; formats += "Dolby Atmos" }
                        "ac4" in t -> formats += "Dolby Atmos"
                        "eac3" in t -> formats += "Dolby Digital Plus"
                        "ac3" in t -> formats += "Dolby Digital"
                        "true-hd" in t || "truehd" in t -> formats += "Dolby TrueHD"
                        "dts.uhd" in t || "dts-uhd" in t -> formats += "DTS:X"
                        "dts.hd" in t || "dts-hd" in t -> formats += "DTS-HD"
                        "dts" in t -> formats += "DTS"
                    }
                    val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
                    val ch = runCatching { caps.audioCapabilities?.maxInputChannelCount ?: 0 }.getOrNull() ?: 0
                    if (ch > maxChannels) maxChannels = ch
                }
            }
        } catch (_: Exception) {
        }

        // (2) HDMI / ARC passthrough to an external AVR or soundbar.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val devices = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
                for (d in devices) {
                    if (d.type == AudioDeviceInfo.TYPE_HDMI ||
                        d.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && d.type == AudioDeviceInfo.TYPE_HDMI_EARC)
                    ) {
                        val chans = d.channelCounts
                        if (chans != null && chans.isNotEmpty()) {
                            val hi = chans.max()
                            if (hi > maxChannels) maxChannels = hi
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // (2b) Direct-playback (bitstream passthrough) probe — a sink can pass Atmos/DTS:X even when
        // the device has no software decoder for them. API 29+ only; guarded.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                fun supports(encoding: Int): Boolean = runCatching {
                    val fmt = AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                        .setSampleRate(48000)
                        .build()
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                    AudioTrack.isDirectPlaybackSupported(fmt, attrs)
                }.getOrDefault(false)

                if (supports(AudioFormat.ENCODING_E_AC3_JOC)) { formats += "Dolby Digital Plus"; formats += "Dolby Atmos" }
                if (supports(AudioFormat.ENCODING_AC4)) formats += "Dolby Atmos"
                if (supports(AudioFormat.ENCODING_E_AC3)) formats += "Dolby Digital Plus"
                if (supports(AudioFormat.ENCODING_AC3)) formats += "Dolby Digital"
                if (supports(AudioFormat.ENCODING_DOLBY_TRUEHD)) formats += "Dolby TrueHD"
                if (supports(AudioFormat.ENCODING_DTS)) formats += "DTS"
                if (supports(AudioFormat.ENCODING_DTS_HD)) formats += "DTS-HD"
            }
        } catch (_: Exception) {
        }

        val label = when {
            maxChannels >= 8 -> "7.1"
            maxChannels >= 6 -> "5.1"
            else -> "2.0"
        }
        return AudioCaps(label, formats.toList())
    }

    /**
     * Mirror the just-detected caps into the shared [DeviceCapabilities] holder that commonMain UI
     * (stream-picker badge downgrade pills) reads. Kept in lockstep with the JSON body PUT to the
     * backend so the client-side "will be downgraded" hint matches what the backend actually serves.
     */
    private fun publishSharedCapabilities(
        maxResolution: String,
        hdrTypes: List<String>,
        audio: AudioCaps,
    ) {
        val vertical = when (maxResolution) {
            "2160p" -> 2160
            "1080p" -> 1080
            "720p" -> 720
            else -> 480
        }
        // hdrTypes labels come from detectHdrTypes: "DolbyVision" | "HDR10+" | "HDR10" | "HLG".
        val hdrRank = when {
            hdrTypes.any { it.equals("DolbyVision", ignoreCase = true) } -> 4
            hdrTypes.any { it.equals("HDR10+", ignoreCase = true) } -> 3
            hdrTypes.any { it.equals("HDR10", ignoreCase = true) } -> 2
            hdrTypes.any { it.equals("HLG", ignoreCase = true) || it.equals("HDR", ignoreCase = true) } -> 1
            else -> 0
        }
        val maxChannels = when (audio.maxChannelsLabel) {
            "7.1" -> 8
            "5.1" -> 6
            else -> 2
        }
        // Only the object/lossless formats matter for the audioObject downgrade decision.
        val objectFormats = audio.formats.filter { fmt ->
            fmt.equals("Dolby Atmos", ignoreCase = true) ||
                fmt.equals("Dolby TrueHD", ignoreCase = true) ||
                fmt.equals("DTS:X", ignoreCase = true) ||
                fmt.equals("DTS-HD", ignoreCase = true)
        }.toSet()
        runCatching {
            DeviceCapabilities.publish(
                DeviceCapabilitiesSnapshot(
                    maxResolutionVertical = vertical,
                    hdrRank = hdrRank,
                    maxAudioChannels = maxChannels,
                    objectAudioFormats = objectFormats,
                ),
            )
        }
    }
}
