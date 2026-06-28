package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.BackendAuth
import com.nuvio.app.core.network.PrivateBackend
import com.nuvio.app.features.addons.encodeAddonPathSegment
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Calls the backend force re-scrape ("Force fetch") endpoint:
 *   POST {catalogAddonUrl}/stream/{type}/{video_id}/rescrape
 *
 * Same host / path-prefix / auth as the catalog-addon GET /stream/{type}/{video_id}.json that
 * [StreamsRepository] already calls — only the suffix differs (`.json` GET → `/rescrape` POST).
 * The backend re-scrapes ONE title/episode and upserts hashes; the caller should re-fetch the
 * stream list afterwards so newly-found streams appear.
 *
 * Rate limited 1 request / 60s per (user, title) → 429 with a Retry-After header.
 *
 * Mirrors NuvioTV's `ForceRescrapeService`.
 */
object ForceRescrapeService {
    private val log = Logger.withTag("ForceRescrapeService")
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        /** 200 — `ok` reflects whether the scrape actually produced something. */
        data class Success(
            val ok: Boolean,
            val added: Int,
            val valid: Int,
            val cached: Int,
        ) : Result

        /** 429 — per-(user,title) cooldown hit; [retryAfterSeconds] from the header if present. */
        data class RateLimited(val retryAfterSeconds: Int?) : Result

        /** Any other failure (401/400/404/5xx/network). */
        data class Failure(val code: Int?) : Result
    }

    /**
     * @param type "movie" | "series"
     * @param videoId the IDENTICAL id the stream screen holds (e.g. "tt1375666" or "tt0903747:1:2")
     */
    suspend fun rescrape(type: String, videoId: String): Result {
        // Same base + prefix as the GET stream-list fetch, only the suffix differs.
        val url = PrivateBackend.withDeviceProfile(
            "${PrivateBackend.catalogAddonUrl}/stream/" +
                "${type.encodeAddonPathSegment()}/${videoId.encodeAddonPathSegment()}/rescrape"
        )
        val headers = buildMap {
            // BackendAuth attaches the Bearer token for our host inside httpRequestRaw, but we
            // still add the active profile id explicitly (matching the reco/ratings contract).
            put("X-Profile-Id", ProfileRepository.activeProfileId.toString())
            put("Accept", "application/json")
        }
        return runCatching {
            val response = httpRequestRaw(
                method = "POST",
                url = url,
                headers = headers,
                body = "",
            )
            when {
                response.status in 200..299 -> {
                    val obj = runCatching {
                        json.parseToJsonElement(response.body).jsonObject
                    }.getOrNull()
                    Result.Success(
                        ok = obj?.get("ok")?.jsonPrimitive?.booleanOrNull ?: false,
                        added = obj?.get("added")?.jsonPrimitive?.intOrNull ?: 0,
                        valid = obj?.get("valid")?.jsonPrimitive?.intOrNull ?: 0,
                        cached = obj?.get("cached")?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }
                response.status == 429 -> {
                    val retryAfter = response.headers["retry-after"]?.trim()?.toIntOrNull()
                    Result.RateLimited(retryAfter)
                }
                else -> {
                    log.w { "rescrape failed code=${response.status} url=$url" }
                    Result.Failure(response.status)
                }
            }
        }.getOrElse { err ->
            log.w(err) { "rescrape threw for url=$url" }
            Result.Failure(null)
        }
    }
}
