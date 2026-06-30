package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mobile port of NuvioTV's lightweight cacheStatus poll (TV commit 2bfc971d).
 *
 * The full stream list (`/stream/{type}/{id}.json`) carries `Cache-Control: max-age=60`, so
 * re-fetching it to refresh the cache pill serves a STALE "downloading" status. The backend
 * therefore exposes an UNCACHED status endpoint:
 *
 *   GET {baseUrl}/stream/{type}/{video_id}/status
 *     -> {"items":[{ info_hash, cached(bool), has_url(bool), ... }]}
 *
 * This object fetches that endpoint and returns a `info_hash -> [StreamCacheState]` promotion
 * map. The caller ([StreamsRepository]) applies it to the on-screen streams, PROMOTING each
 * stream's cacheStatus toward instant/cached and NEVER downgrading — so the pill flips live
 * while the user is on the stream list / details (where prewarm runs).
 *
 * Auth + host: built via [com.nuvio.app.core.network.PrivateBackend.baseUrl] and fetched through
 * [httpGetText], which attaches the same host-matched Supabase Bearer the stream calls use
 * (see AddonPlatform.executeTextRequest / BackendAuth.authHeadersFor).
 */
object StreamStatusPoller {
    private val log = Logger.withTag("StreamStatus")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The cache state a status item maps to, or null when it carries no promotable signal.
     * Mapping (mirrors TV): `has_url == true` -> INSTANT, else `cached == true` -> CACHED.
     */
    enum class StatusTier { INSTANT, CACHED }

    /**
     * Fetches `/stream/{type}/{videoId}/status` and returns a map keyed by NORMALIZED (trimmed,
     * lower-cased) info_hash → highest promotable tier. Returns an empty map on any failure
     * (best-effort; the caller leaves the current pill state untouched).
     *
     * [videoId] must already be the Stremio stream id the list was loaded with (i.e. for series
     * the `id:season:episode` form), matching how [StreamsRepository] builds its stream URL.
     */
    suspend fun fetch(type: String, videoId: String): Map<String, StatusTier> {
        val base = com.nuvio.app.core.network.PrivateBackend.baseUrl
        val url = "$base/stream/${type.trim()}/${videoId.trim()}/status"
        val payload = try {
            httpGetText(url)
        } catch (e: Exception) {
            log.d { "status fetch failed for $type/$videoId: ${e.message}" }
            return emptyMap()
        }

        val items = try {
            (json.parseToJsonElement(payload).jsonObject["items"] as? JsonArray).orEmptyArray()
        } catch (e: Exception) {
            log.d { "status parse failed for $type/$videoId: ${e.message}" }
            return emptyMap()
        }

        val out = mutableMapOf<String, StatusTier>()
        for (element in items) {
            val obj = element as? JsonObject ?: continue
            val hash = obj.stringField("info_hash")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
            val hasUrl = obj.booleanField("has_url") == true
            val cached = obj.booleanField("cached") == true
            val tier = when {
                hasUrl -> StatusTier.INSTANT
                cached -> StatusTier.CACHED
                else -> null
            } ?: continue
            // Keep the strongest tier seen for a hash (INSTANT outranks CACHED).
            val existing = out[hash]
            if (existing == null || (existing == StatusTier.CACHED && tier == StatusTier.INSTANT)) {
                out[hash] = tier
            }
        }
        return out
    }

    private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())

    private fun JsonObject.stringField(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.booleanField(name: String): Boolean? =
        this[name]?.jsonPrimitive?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
}
