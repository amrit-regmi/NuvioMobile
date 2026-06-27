package com.nuvio.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
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
        val maxResolution = detectMaxResolution(context, decode)
        val hdrTypes = detectHdrTypes(context, decode)
        val codecs = detectCodecs()
        val formFactor = detectFormFactor(context)
        val appVersion = detectAppVersion(context)
        // Bound stream size so the backend never returns a remux this device can't comfortably
        // stream. Derived from the (decode-capped) resolution since we don't yet measure bandwidth.
        val maxSizeGb = when (maxResolution) {
            "2160p" -> 45
            "1080p" -> 15
            else -> 5
        }
        // Report the active network's estimated downstream bandwidth so the backend can right-size
        // the stream to the link, not just the decoder. 0 when unknown (no permission needed).
        val downloadSpeedMbps = detectDownstreamMbps(context)

        val body = buildString {
            append("{")
            append("\"device_id\":\"$deviceId\",")
            append("\"user_id\":\"$userId\",")
            append("\"device_name\":\"${Build.MODEL}\",")
            append("\"form_factor\":\"$formFactor\",")
            append("\"app_version\":\"$appVersion\",")
            append("\"max_resolution\":\"$maxResolution\",")
            append("\"hdr_types_supported\":${hdrTypes.joinToString(",", "[", "]") { "\"$it\"" }},")
            append("\"max_audio_channels\":\"7.1\",")
            append("\"preferred_audio_formats\":[\"Dolby Atmos\",\"DTS:X\",\"AAC\"],")
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
                "hdr=$hdrTypes codecs=$codecs maxSizeGb=$maxSizeGb downMbps=$downloadSpeedMbps form=$formFactor " +
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

    private fun detectMaxResolution(context: Context, decode: DecodeCaps): String {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val panelHeight = display?.supportedModes?.maxOfOrNull { it.physicalHeight } ?: 1080
        // Effective height = min(what the panel shows, what the decoder can decode). Never report
        // below 1080p (avoids under-serving when a decoder probe comes back unexpectedly low).
        val decodeHeight = if (decode.maxHeight > 0) decode.maxHeight else Int.MAX_VALUE
        val effective = maxOf(minOf(panelHeight, decodeHeight), 1080)
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
}
