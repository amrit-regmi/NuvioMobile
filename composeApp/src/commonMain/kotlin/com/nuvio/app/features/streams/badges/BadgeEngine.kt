package com.nuvio.app.features.streams.badges

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import com.nuvio.app.core.device.DeviceCapabilitiesSnapshot
import com.nuvio.app.features.streams.StreamBadgeMatcher
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Local (bundled) "Elite-Badges" logo engine for the STREAM PICKER rows.
 *
 * Distinct from the URL-imported [com.nuvio.app.features.streams.StreamBadgeRules] system (which
 * renders remote text/image tags). This engine:
 *   1. loads a bundled ruleset (`composeResources/files/badges.json`) + PNG logos once,
 *   2. regex-matches each stream's release name against the ruleset,
 *   3. reduces to at most one badge per group in a fixed display order, and
 *   4. flags badges whose advertised capability EXCEEDS this device (4K on a 1080p panel, Atmos on
 *      a stereo device, ...) with a client-side "↓ <target>" downgrade hint.
 *
 * Everything is defensive: a bad regex, a missing asset, or a parse failure degrades to "no badge"
 * and never throws into a Compose row.
 */
object BadgeEngine {
    private val log = Logger.withTag("BadgeEngine")

    private const val RULESET_PATH = "files/badges.json"
    private const val ASSET_DIR = "files/badges"

    // Fixed left-to-right group render order.
    private val GROUP_ORDER = listOf(
        "resolution",
        "source",
        "video-tech",
        "video-codec",
        "bit-depth",
        "audio-tech",
        "audio-channels",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var compiledRules: List<CompiledBadgeRule>? = null
    private val rulesMutex = Mutex()

    private val imageCache = mutableMapOf<String, ImageBitmap?>()
    private val imageMutex = Mutex()

    /**
     * Parse + compile the bundled ruleset once (cached). Safe to call repeatedly; concurrent callers
     * share the single compile. Returns empty on any failure.
     */
    suspend fun rules(): List<CompiledBadgeRule> {
        compiledRules?.let { return it }
        return rulesMutex.withLock {
            compiledRules?.let { return@withLock it }
            val compiled = runCatching { loadRules() }.getOrElse {
                log.w(it) { "Failed to load bundled badge ruleset" }
                emptyList()
            }
            compiledRules = compiled
            compiled
        }
    }

    private suspend fun loadRules(): List<CompiledBadgeRule> {
        val bytes = Res.readBytes(RULESET_PATH)
        val payload = json.decodeFromString<BadgeRulesetPayload>(bytes.decodeToString())
        val compiled = payload.filters.mapIndexedNotNull { index, filter ->
            val pattern = filter.pattern?.trim().orEmpty()
            val asset = filter.asset?.trim().orEmpty()
            val group = filter.group?.trim().orEmpty()
            if (pattern.isEmpty() || asset.isEmpty() || group.isEmpty()) return@mapIndexedNotNull null
            val regex = runCatching { Regex(pattern) }.getOrNull() ?: run {
                log.w { "Skipping badge '${filter.id}': bad regex" }
                return@mapIndexedNotNull null
            }
            CompiledBadgeRule(
                id = filter.id?.trim().orEmpty().ifEmpty { asset },
                group = group,
                name = filter.name?.trim().orEmpty().ifEmpty { filter.id.orEmpty() },
                asset = asset,
                regex = regex,
                capType = filter.capType?.trim()?.lowercase(),
                capRank = filter.capRank,
                downgradeLabel = filter.downgradeLabel?.trim(),
                order = index,
            )
        }
        log.d { "Compiled ${compiled.size} bundled badge rules" }
        return compiled
    }

    /**
     * Match a stream's release name, reduce to one badge per group, and attach downgrade hints for
     * the given device. Result is ordered by [GROUP_ORDER]. Empty when nothing matched.
     */
    suspend fun matchedBadges(
        stream: StreamItem,
        device: DeviceCapabilitiesSnapshot,
    ): List<MatchedBadge> {
        val rules = rules()
        if (rules.isEmpty()) return emptyList()

        // Reuse the existing candidate extraction so we match the SAME release-name strings the app
        // already trusts (raw filename first, then torrent name, parsed fields, display name, ...).
        val candidates = runCatching { StreamBadgeMatcher.badgeMatchCandidates(stream) }
            .getOrDefault(emptyList())
        if (candidates.isEmpty()) return emptyList()

        // Best match per group: highest capRank, ties broken by file order.
        val bestPerGroup = mutableMapOf<String, CompiledBadgeRule>()
        rules.forEach { rule ->
            val hit = runCatching {
                candidates.any { rule.regex.containsMatchIn(it) }
            }.getOrDefault(false)
            if (!hit) return@forEach
            val existing = bestPerGroup[rule.group]
            if (existing == null ||
                (rule.capRank ?: Int.MIN_VALUE) > (existing.capRank ?: Int.MIN_VALUE)
            ) {
                bestPerGroup[rule.group] = rule
            }
        }
        if (bestPerGroup.isEmpty()) return emptyList()

        val ordered = GROUP_ORDER.mapNotNull { bestPerGroup[it] } +
            bestPerGroup.filterKeys { it !in GROUP_ORDER }.values

        return ordered.map { rule ->
            MatchedBadge(rule = rule, downgradeTarget = computeDowngradeTarget(rule, device))
        }
    }

    /**
     * Downgrade target label for a badge vs THIS device, or null when native / not applicable.
     * Uses the ruleset's [CompiledBadgeRule.capType]/[CompiledBadgeRule.capRank].
     */
    private fun computeDowngradeTarget(
        rule: CompiledBadgeRule,
        device: DeviceCapabilitiesSnapshot,
    ): String? {
        val rank = rule.capRank
        return when (rule.capType) {
            "resolution" -> {
                if (rank != null && rank > device.maxResolutionVertical) device.maxResolutionLabel else null
            }
            "hdr" -> {
                if (rank != null && rank > device.hdrRank) device.hdrLabel else null
            }
            "audiochannels" -> {
                if (rank != null && rank > device.maxAudioChannels) device.audioChannelsLabel else null
            }
            "audioobject" -> {
                // Object/lossless audio (Atmos/TrueHD/DTS:X/DTS-HD MA). Downgradeable when the device
                // can't render that specific format; it falls back to the lossy "core". We use the
                // badge's downgradeLabel (e.g. "Atmos", "TrueHD", "DTS:X") as the format key. If
                // per-format support isn't cleanly detected, this approximates via the object-format
                // set the platform reported (union of on-board decoders + HDMI bitstream passthrough).
                // TODO: if backend ever exposes exact per-track object-audio support, prefer that.
                val label = rule.downgradeLabel
                if (label != null && !device.supportsObjectAudio(label)) "core" else null
            }
            else -> null
        }
    }

    /**
     * Decode + cache the badge PNG as an [ImageBitmap]. Cached (including negative results) so each
     * asset decodes at most once and is reused across every row. Returns null on failure.
     */
    suspend fun image(asset: String): ImageBitmap? {
        imageCache[asset]?.let { return it }
        return imageMutex.withLock {
            if (imageCache.containsKey(asset)) return@withLock imageCache[asset]
            val bitmap = runCatching {
                Res.readBytes("$ASSET_DIR/$asset").decodeToImageBitmap()
            }.getOrElse {
                log.w(it) { "Failed to decode badge asset $asset" }
                null
            }
            imageCache[asset] = bitmap
            bitmap
        }
    }
}

/** A ruleset entry with its compiled regex + capability tags. */
data class CompiledBadgeRule(
    val id: String,
    val group: String,
    val name: String,
    val asset: String,
    val regex: Regex,
    val capType: String?,
    val capRank: Int?,
    val downgradeLabel: String?,
    val order: Int,
)

/** A badge that matched a stream, plus its per-device downgrade target (null = native). */
data class MatchedBadge(
    val rule: CompiledBadgeRule,
    val downgradeTarget: String?,
)

@Serializable
private data class BadgeRulesetPayload(
    val version: Int? = null,
    val source: String? = null,
    val filters: List<BadgeFilterPayload> = emptyList(),
)

@Serializable
private data class BadgeFilterPayload(
    val id: String? = null,
    val group: String? = null,
    val name: String? = null,
    val pattern: String? = null,
    val asset: String? = null,
    val capType: String? = null,
    val capRank: Int? = null,
    val downgradeLabel: String? = null,
)
