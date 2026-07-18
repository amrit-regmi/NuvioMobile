package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.BackendAuth
import com.nuvio.app.core.network.PrivateBackend
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Aggregated external ratings now come EXCLUSIVELY from OUR backend
 * (`GET /catalog-addon/ratings/{imdbId}.json` → `{ratings:[{source,value,votes}]}`), mirroring
 * NuvioTV's `MDBListRepository`. The app NO LONGER calls `api.mdblist.com` directly and NO LONGER
 * requires a client-side MDBList API key.
 *
 * Ratings are ALWAYS ON. The defunct per-provider toggles + api-key entry (still surfaced in the
 * MDBList settings page) no longer gate or filter anything — every stored source is returned. The
 * request carries the Supabase user Bearer (via [BackendAuth.authHeadersFor], host-scoped to our
 * backend, same as /reco + /prewarm). The backend ratings table may be empty (e.g. server-side
 * MDBLIST_API_KEY not set) → we degrade gracefully to no extra ratings, never error.
 *
 * The imdb id is resolved EXCLUSIVELY from ids OUR backend already provides (meta.id /
 * fallbackItemId / rawItemId — all carry `tt…`). NO external calls, no client-side TMDB conversion.
 */
object MdbListMetadataService {
    const val PROVIDER_IMDB = "imdb"
    const val PROVIDER_TMDB = "tmdb"
    const val PROVIDER_TOMATOES = "tomatoes"
    const val PROVIDER_METACRITIC = "metacritic"
    const val PROVIDER_TRAKT = "trakt"
    const val PROVIDER_LETTERBOXD = "letterboxd"
    const val PROVIDER_AUDIENCE = "audience"

    val PROVIDER_PRIORITY_ORDER = listOf(
        PROVIDER_IMDB,
        PROVIDER_TMDB,
        PROVIDER_TOMATOES,
        PROVIDER_METACRITIC,
        PROVIDER_TRAKT,
        PROVIDER_LETTERBOXD,
        PROVIDER_AUDIENCE,
    )

    private val log = Logger.withTag("MdbListMetadata")
    private val json = Json { ignoreUnknownKeys = true }

    // Cache keyed by imdb id (the backend endpoint is imdb-only). Only genuine outcomes (incl. a
    // valid-but-empty list) are cached; transient failures (missing token / non-2xx / network) are
    // NOT cached so the next detail-open retries. Guarded by a Mutex for the concurrent enrich
    // passes (initial meta fetch + meta-screen re-enrich).
    private val ratingsCache = mutableMapOf<String, List<MetaExternalRating>>()
    private val cacheMutex = Mutex()
    private val imdbRegex = Regex("tt\\d+")

    /**
     * True whenever an imdb id can be resolved from [meta]/[fallbackItemId] — ratings are always on
     * now, so the only precondition is having an id to look up. [settings] is ignored (kept for
     * call-site compatibility with the defunct toggle model).
     */
    fun shouldFetchForMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: MdbListSettings,
    ): Boolean = (extractImdbId(meta.id) ?: extractImdbId(fallbackItemId)) != null

    suspend fun enrichMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: MdbListSettings,
    ): MetaDetails {
        val imdbId = extractImdbId(meta.id)
            ?: extractImdbId(fallbackItemId)
            ?: return meta.copy(externalRatings = emptyList())
        val ratings = getCachedOrFetch(imdbId)
        return meta.copy(externalRatings = ratings)
    }

    fun clearCache() {
        // Plain clear (no lock): callers are non-suspend settings-change events; a racy clear of a
        // rarely-mutated cache is harmless (worst case a concurrent fetch re-populates one entry).
        ratingsCache.clear()
    }

    /**
     * Fetches the full aggregated rating set for a raw meta [rawItemId] (a bare `tt…` id or a
     * `tmdb:…` id that embeds one) of [metaType]. Shares the SAME backend request path + cache the
     * details screen uses, so a home-hero item the user later opens costs nothing on the details
     * side (and vice-versa). Returns empty when no imdb id resolves. [settings] is ignored.
     */
    suspend fun fetchAggregatedRatings(
        rawItemId: String?,
        metaType: String,
        settings: MdbListSettings,
    ): List<MetaExternalRating> {
        val imdbId = extractImdbId(rawItemId) ?: return emptyList()
        return getCachedOrFetch(imdbId)
    }

    /** Returns cached ratings, or fetches from the backend and caches genuine outcomes. */
    private suspend fun getCachedOrFetch(imdbId: String): List<MetaExternalRating> {
        cacheMutex.withLock { ratingsCache[imdbId] }?.let { return it }
        val fetched = fetchFromBackend(imdbId) ?: return emptyList()
        cacheMutex.withLock { ratingsCache[imdbId] = fetched }
        return fetched
    }

    /**
     * GET `/catalog-addon/ratings/{imdb}.json` with the Supabase bearer. Returns the parsed list on
     * success (incl. a valid-but-empty list); returns null on a transient failure (no token yet /
     * non-2xx / network) so the caller does NOT negative-cache it.
     */
    private suspend fun fetchFromBackend(imdbId: String): List<MetaExternalRating>? =
        withContext(Dispatchers.Default) {
            if (PrivateBackend.baseUrl.isBlank()) return@withContext null
            val url = "${PrivateBackend.catalogAddonUrl}/ratings/$imdbId.json"
            val headers = BackendAuth.authHeadersFor(url)
            if (!headers.containsKey("Authorization")) return@withContext null
            try {
                val body = httpGetTextWithHeaders(url, headers)
                val parsed = json.decodeFromString<RatingsResponse>(body)
                parsed.ratings.mapNotNull { it.toExternalRating() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w { "Backend ratings fetch failed for $imdbId (transient; not cached): ${e.message}" }
                null
            }
        }

    private fun RatingItem.toExternalRating(): MetaExternalRating? {
        val v = value ?: return null
        val normalized = when (source?.trim()?.lowercase()) {
            "imdb" -> PROVIDER_IMDB
            "tmdb" -> PROVIDER_TMDB
            "trakt" -> PROVIDER_TRAKT
            "letterboxd" -> PROVIDER_LETTERBOXD
            "tomatoes", "rottentomatoes", "rotten_tomatoes", "tomatometer" -> PROVIDER_TOMATOES
            "audience", "rt_audience", "tomatoesaudience" -> PROVIDER_AUDIENCE
            "metacritic", "metascore" -> PROVIDER_METACRITIC
            else -> return null
        }
        return MetaExternalRating(source = normalized, value = v)
    }

    private fun extractImdbId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return imdbRegex.find(value)?.value
    }
}

@Serializable
private data class RatingsResponse(
    val ratings: List<RatingItem> = emptyList(),
)

@Serializable
private data class RatingItem(
    val source: String? = null,
    val value: Double? = null,
    val votes: Int? = null,
)
