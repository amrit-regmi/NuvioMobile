package com.nuvio.app.features.home

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.BackendAuth
import com.nuvio.app.core.network.PrivateBackend
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single personalized recommendation row from our FastAPI backend's reco engine.
 *
 * Mirrors NuvioTV's `RecommendationRepository.fetchRows` (`GET /reco/home/{user}` →
 * `RecoResponse { rows: [ RecoRow { label, reason_type, content_type, items } ] }`).
 */
data class RecoRow(
    val label: String,
    val reasonType: String,
    val contentType: String?,
    val items: List<MetaPreview>,
) {
    /** Stable key for this reco row, used as the home-catalog ordering key. */
    val key: String
        get() = "${RECO_ADDON_ID}:${contentType.orEmpty()}:${reasonType}"

    /**
     * Single source of truth for turning a reco row into its [HomeCatalogDefinition].
     * Both [HomeRepository] (reco sections) and [HomeCatalogSettingsRepository] (reco ordering)
     * use this so the reco definition is never constructed two divergent ways.
     */
    fun toHomeCatalogDefinition(): HomeCatalogDefinition =
        HomeCatalogDefinition(
            key = key,
            defaultTitle = label,
            addonName = RECO_ADDON_ID,
            manifestUrl = "",
            type = contentType ?: "movie",
            catalogId = reasonType,
            supportsPagination = false,
            isReco = true,
        )
}

/** Synthetic addon id for reco rows, mirroring the TV app's `reco_engine`. */
const val RECO_ADDON_ID = "reco_engine"

/**
 * Fetches personalized recommendation rows from OUR backend (`/reco/home/{user_id}`).
 *
 * Identity is Bearer-derived server-side (the `{user_id}` path param is decorative), and
 * we additionally pass `?profile_id={numeric_profile_id}` as a query param so reco scoping
 * matches the TV app's per-profile recommendations. The recommendations master toggle
 * ([HomeCatalogSettingsRepository.useRecommendations]) decides whether this is called at all.
 */
object RecommendationService {
    private val log = Logger.withTag("RecommendationService")
    private val json = Json { ignoreUnknownKeys = true }

    private const val LIMIT_PER_ROW = 18

    /** Returns reco rows for the active signed-in profile, or empty when unavailable. */
    suspend fun fetchRows(): List<RecoRow> {
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous) return emptyList()
        val userId = authState.userId

        // TV calls: GET /reco/home/{supabase_uuid}?profile_id={numeric_id}&limit_per_row=N
        val profileId = ProfileRepository.activeProfileId
        val url = "${PrivateBackend.recoBaseUrl}/home/$userId?profile_id=$profileId&limit_per_row=$LIMIT_PER_ROW"
        val headers = buildMap {
            putAll(BackendAuth.authHeadersFor(url))
            put("X-Profile-Id", profileId.toString())
        }
        if (!headers.containsKey("Authorization")) return emptyList()

        return runCatching {
            val payload = httpGetTextWithHeaders(url, headers)
            parseRows(payload)
        }.onFailure { error ->
            log.w(error) { "Failed to fetch reco rows from $url" }
        }.getOrDefault(emptyList())
    }

    private fun parseRows(payload: String): List<RecoRow> {
        val root = json.parseToJsonElement(payload).jsonObject
        val rows = root["rows"]?.jsonArray ?: return emptyList()
        return rows.mapIndexedNotNull { index, element ->
            val obj = element.jsonObject
            val label = obj.str("label")?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val rawReasonType = obj.str("reason_type") ?: "personal"
            // Mirror the TV's _RECO_REASON_TO_DASHBOARD_ID mapping so our keys
            // match the ids written to rowOrder by the TV app.
            val reasonType = when (rawReasonType) {
                "watched_title" -> "because_watched"
                "keyword_theme" -> "genre_theme"
                else -> rawReasonType
            }
            val contentType = when (obj.str("content_type")) {
                "tv", "tvshow", "show" -> "series"
                else -> obj.str("content_type")
            }
            val items = obj["items"]?.jsonArray
                ?.mapNotNull { it.jsonObject.toMetaPreview() }
                .orEmpty()
            if (items.isEmpty()) return@mapIndexedNotNull null
            RecoRow(
                label = label,
                // Disambiguate rows that share reason_type + content_type (e.g. multiple
                // "because_watched") so each reco row gets a unique stable key.
                reasonType = "${reasonType}_$index",
                contentType = contentType,
                items = items,
            )
        }
    }

    private fun JsonObject.toMetaPreview(): MetaPreview? {
        val tmdbId = this["tmdb_id"]?.jsonPrimitive?.intOrNull
        val kind = str("kind")?.lowercase()
        val imdbId = str("imdb_id")
        // Build a catalog-addon-compatible id (idPrefixes: "tt", "tmdb:").
        val type = when (kind) {
            "tv", "series" -> "series"
            else -> "movie"
        }
        val id = when {
            !imdbId.isNullOrBlank() -> imdbId
            tmdbId != null -> "tmdb:${if (type == "series") "series" else "movie"}:$tmdbId"
            else -> return null
        }
        val name = str("title") ?: str("name") ?: return null
        val year = this["year"]?.jsonPrimitive?.intOrNull
        val voteAverage = this["vote_average"]?.jsonPrimitive?.doubleOrNull
        return MetaPreview(
            id = id,
            type = type,
            name = name,
            poster = str("poster") ?: str("poster_path"),
            banner = str("backdrop"),
            logo = str("logo") ?: str("logo_path"),
            description = str("overview"),
            releaseInfo = year?.toString(),
            imdbRating = voteAverage?.let { (it * 10).toInt() / 10.0 }?.toString(),
            genres = this["genres"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty(),
        )
    }

    private fun JsonObject.str(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
}
