package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.BackendAuth
import com.nuvio.app.core.network.PrivateBackend
import com.nuvio.app.features.addons.encodeAddonPathSegment
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Thin client for the backend catalog-addon "download to TorBox" endpoints. Mirrors NuvioTV's
 * `CatalogAddonApi.prepareStream` / `getStreamStatus` / `cancelPrepare`, adapted to mobile's
 * ktor/KMP transport (same auth + url + best-effort idioms as [ForceRescrapeService] and
 * [CatalogPrewarmService]):
 *
 *   POST   {catalogAddonUrl}/stream/{type}/{video_id}/prepare  → start a download. The backend
 *          auto-picks the top-seeded uncached hash (no request body). Returns a
 *          [CatalogStreamPrepareDto] with status ∈ already_cached | queued | no_seeders |
 *          slots_full | (success).
 *   GET    {catalogAddonUrl}/stream/{type}/{video_id}/status   → poll progress; returns
 *          [CatalogStreamStatusDto] with one item per active hash.
 *   DELETE {catalogAddonUrl}/stream/{type}/{video_id}/prepare  → cancel/remove the torrent
 *          (fire-and-forget).
 *
 * Auth: the same Supabase user Bearer token as every other catalog-addon call, attached via
 * [BackendAuth.authHeadersFor]. type = "movie"|"series"; videoId = "tt123" (movie) or
 * "tt123:S:E" (series).
 *
 * Every call is best-effort / non-throwing (matching the TV flow): network or auth failures
 * surface as null / [Result.Failure] rather than propagating, so the UI is never blocked.
 */
object CatalogDownloadApi {
    private val log = Logger.withTag("CatalogDownloadApi")
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface PrepareResult {
        /** 2xx — backend accepted the request; inspect [dto].status for the outcome. */
        data class Success(val dto: CatalogStreamPrepareDto) : PrepareResult

        /** Any non-2xx / network / parse failure. */
        data class Failure(val code: Int?) : PrepareResult
    }

    /** POST /prepare — start a download. Backend auto-picks the top-seeded uncached hash. */
    suspend fun prepare(type: String, videoId: String): PrepareResult {
        val url = streamActionUrl(type, videoId, "prepare")
        return runCatching {
            val headers = authHeaders(url)
            val response = httpRequestRaw(method = "POST", url = url, headers = headers, body = "")
            if (response.status in 200..299) {
                val dto = runCatching {
                    json.decodeFromString(CatalogStreamPrepareDto.serializer(), response.body)
                }.getOrElse { CatalogStreamPrepareDto() }
                PrepareResult.Success(dto)
            } else {
                log.w { "prepare failed code=${response.status} url=$url" }
                PrepareResult.Failure(response.status)
            }
        }.getOrElse { err ->
            if (err is CancellationException) throw err
            log.w(err) { "prepare threw for url=$url" }
            PrepareResult.Failure(null)
        }
    }

    /** GET /status — poll progress. Returns null on any failure (caller keeps polling). */
    suspend fun status(type: String, videoId: String): CatalogStreamStatusDto? {
        val url = streamActionUrl(type, videoId, "status")
        return runCatching {
            val headers = authHeaders(url)
            val response = httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
            if (response.status in 200..299) {
                runCatching {
                    json.decodeFromString(CatalogStreamStatusDto.serializer(), response.body)
                }.getOrNull()
            } else {
                log.w { "status failed code=${response.status} url=$url" }
                null
            }
        }.getOrElse { err ->
            if (err is CancellationException) throw err
            log.w(err) { "status threw for url=$url" }
            null
        }
    }

    /** DELETE /prepare — cancel/remove the torrent from TorBox. Fire-and-forget. */
    suspend fun cancelPrepare(type: String, videoId: String) {
        val url = streamActionUrl(type, videoId, "prepare")
        runCatching {
            val headers = authHeaders(url)
            httpRequestRaw(method = "DELETE", url = url, headers = headers, body = "")
        }.onFailure { err ->
            if (err is CancellationException) throw err
            log.w(err) { "cancelPrepare threw for url=$url (ignored)" }
        }
    }

    private fun streamActionUrl(type: String, videoId: String, action: String): String =
        PrivateBackend.withDeviceProfile(
            "${PrivateBackend.catalogAddonUrl}/stream/" +
                "${type.encodeAddonPathSegment()}/${videoId.encodeAddonPathSegment()}/$action"
        )

    private fun authHeaders(url: String): Map<String, String> = buildMap {
        putAll(BackendAuth.authHeadersFor(url))
        put("X-Profile-Id", ProfileRepository.activeProfileId.toString())
        put("Accept", "application/json")
    }
}

@Serializable
data class CatalogStreamPrepareDto(
    @SerialName("status") val status: String? = null,
    @SerialName("eta_minutes") val etaMinutes: Int? = null,
    @SerialName("info_hash") val infoHash: String? = null,
    @SerialName("torrent_id") val torrentId: String? = null,
)

@Serializable
data class CatalogStreamStatusDto(
    @SerialName("items") val items: List<CatalogStreamStatusItemDto> = emptyList(),
)

@Serializable
data class CatalogStreamStatusItemDto(
    @SerialName("info_hash") val infoHash: String? = null,
    @SerialName("cached") val cached: Boolean? = null,
    @SerialName("has_url") val hasUrl: Boolean? = null,
    @SerialName("progress_pct") val progressPct: Double? = null,
    @SerialName("seeds") val seeds: Int? = null,
    @SerialName("download_speed_mbps") val downloadSpeedMbps: Double? = null,
    @SerialName("eta_seconds") val etaSeconds: Int? = null,
    // download_state ∈ "queued" | "downloading" | … ("queued" = waiting for a free TorBox slot).
    @SerialName("download_state") val downloadState: String? = null,
    // 1-based position in the download queue when download_state == "queued". Absent = unknown.
    @SerialName("queue_position") val queuePosition: Int? = null,
)
