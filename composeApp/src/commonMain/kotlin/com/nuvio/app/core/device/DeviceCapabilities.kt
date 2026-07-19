package com.nuvio.app.core.device

import kotlin.concurrent.Volatile

/**
 * Shared, commonMain-visible snapshot of THIS device's real playback capabilities.
 *
 * The values are the exact same ones the platform detector (Android:
 * `com.nuvio.app.DeviceCapabilityRegistrar`) computes and PUTs to the backend
 * `/catalog-addon/device-profile` endpoint — max decodable resolution, panel+decoder
 * HDR formats, and audio channel/format support. The backend uses them to right-size the
 * returned stream list (`?profile=<id>`); we ALSO keep a client-side copy here so UI can
 * reason about "this stream's release badge advertises a capability my device can't render
 * natively, so it will be downgraded" without a round-trip.
 *
 * Backend device-cap filtering already removes streams the device can't decode at all; this
 * holder is purely for DISPLAY (the amber "↓ target" downgrade pill on stream-picker badges).
 *
 * Platform code publishes a snapshot via [publish] once detection completes. Until then a
 * conservative default is exposed (1080p / SDR / stereo) so badges never over-promise. Every
 * field is nullable-safe and the whole thing is a plain immutable data class behind a volatile
 * ref, so reads from Compose are cheap and never crash.
 */
data class DeviceCapabilitiesSnapshot(
    /** Max vertical pixels the hardware can DECODE (not the panel), e.g. 2160 / 1080 / 720. */
    val maxResolutionVertical: Int = 1080,
    /**
     * Best HDR tier the device can both display AND decode, ranked to match the badge ruleset:
     * Dolby Vision=4, HDR10+=3, HDR10=2, HDR (generic/HLG)=1, SDR=0.
     */
    val hdrRank: Int = 0,
    /** Max renderable audio channel count (7.1→8, 5.1→6, stereo→2). */
    val maxAudioChannels: Int = 2,
    /**
     * Object/lossless audio formats the device can render (decode on-board OR bitstream
     * passthrough): "Dolby Atmos", "Dolby TrueHD", "DTS:X", "DTS-HD" ... Matches the labels the
     * detector reports in `preferred_audio_formats`. Used to decide whether an Atmos/TrueHD/DTS:X
     * badge downgrades to its lossy "core".
     */
    val objectAudioFormats: Set<String> = emptySet(),
) {
    /** Human label for [maxResolutionVertical], reused as the downgrade target text. */
    val maxResolutionLabel: String
        get() = when {
            maxResolutionVertical >= 2160 -> "4K"
            maxResolutionVertical >= 1080 -> "1080p"
            maxResolutionVertical >= 720 -> "720p"
            else -> "480p"
        }

    /** Human label for [hdrRank], reused as the downgrade target text. */
    val hdrLabel: String
        get() = when {
            hdrRank >= 4 -> "Dolby Vision"
            hdrRank >= 3 -> "HDR10+"
            hdrRank >= 2 -> "HDR10"
            hdrRank >= 1 -> "HDR"
            else -> "SDR"
        }

    /** Human label for [maxAudioChannels], reused as the downgrade target text. */
    val audioChannelsLabel: String
        get() = when {
            maxAudioChannels >= 8 -> "7.1"
            maxAudioChannels >= 6 -> "5.1"
            maxAudioChannels >= 2 -> "Stereo"
            else -> "Mono"
        }

    /** True when this device can render the given object-audio format label (case-insensitive). */
    fun supportsObjectAudio(label: String): Boolean =
        objectAudioFormats.any { it.equals(label, ignoreCase = true) }
}

object DeviceCapabilities {
    @Volatile
    var current: DeviceCapabilitiesSnapshot = DeviceCapabilitiesSnapshot()
        private set

    /** Publish a freshly-detected snapshot. Called by platform capability detection. */
    fun publish(snapshot: DeviceCapabilitiesSnapshot) {
        current = snapshot
    }
}
