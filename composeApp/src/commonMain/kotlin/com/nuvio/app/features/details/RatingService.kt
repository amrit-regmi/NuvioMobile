package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.BackendAuth
import com.nuvio.app.core.network.PrivateBackend
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Submits + reads the user's explicit 1–10 star rating for a title via OUR backend
 * (`POST/GET /ratings/{user_id}/...`). Explicit ratings are the strongest reco-bootstrap
 * signal (the backend folds the ALS vector synchronously). Mirrors NuvioTV's
 * `RecoRatingService`.
 *
 *  - POST `/ratings/{user_id}` body `{tmdb_id, kind, stars, source:"nuvio"}` (kind = movie|tv)
 *  - GET  `/ratings/{user_id}/{kind}/{tmdb_id}` → `{stars}` (404 = none)
 *
 * The path `{user_id}` is decorative (token-derived server-side); we still send the active
 * profile via `X-Profile-Id` for per-profile scoping, matching the reco/TV contract.
 */
object RatingService {
    private val log = Logger.withTag("RatingService")
    private val json = Json { ignoreUnknownKeys = true }

    /** Resolves the numeric TMDB id for a meta [id] of [type] (movie/series). */
    suspend fun resolveTmdbId(id: String, type: String): Int? {
        // Fast path: id already encodes a tmdb id (e.g. "tmdb:movie:550").
        val direct = id
            .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.substringBefore('/')
            ?.toIntOrNull()
        if (direct != null) return direct
        val mediaType = if (type.equals("series", true) || type.equals("tv", true)) "tv" else "movie"
        return TmdbService.ensureTmdbId(id, mediaType)?.toIntOrNull()
    }

    /** Returns the existing 1–10 rating for this title, or null. */
    suspend fun fetchRating(tmdbId: Int, type: String): Int? {
        val userId = currentUserId() ?: return null
        val kind = backendKind(type)
        val url = "${PrivateBackend.recoBaseUrl.removeSuffix("/reco")}/ratings/$userId/$kind/$tmdbId"
        val headers = authHeaders(url) ?: return null
        return runCatching {
            val body = httpGetTextWithHeaders(url, headers)
            json.parseToJsonElement(body).jsonObject["stars"]?.jsonPrimitive?.intOrNull
        }.getOrNull()
    }

    /** Submits a 1–10 [stars] rating; returns true on success. */
    suspend fun submitRating(tmdbId: Int, type: String, stars: Int): Boolean {
        val userId = currentUserId() ?: return false
        val kind = backendKind(type)
        val url = "${PrivateBackend.recoBaseUrl.removeSuffix("/reco")}/ratings/$userId"
        val headers = (authHeaders(url) ?: return false) + ("Content-Type" to "application/json")
        val payload = """{"tmdb_id":$tmdbId,"kind":"$kind","stars":${stars.coerceIn(1, 10)},"source":"nuvio"}"""
        return runCatching {
            httpPostJsonWithHeaders(url, payload, headers)
            true
        }.onFailure { log.w(it) { "submitRating failed for $kind/$tmdbId" } }
            .getOrDefault(false)
    }

    private fun backendKind(type: String): String =
        if (type.equals("series", true) || type.equals("tv", true)) "tv" else "movie"

    private fun currentUserId(): String? {
        val state = AuthRepository.state.value
        return (state as? AuthState.Authenticated)?.takeIf { !it.isAnonymous }?.userId
    }

    private fun authHeaders(url: String): Map<String, String>? {
        val base = BackendAuth.authHeadersFor(url)
        if (!base.containsKey("Authorization")) return null
        return base + ("X-Profile-Id" to ProfileRepository.activeProfileId.toString())
    }
}
