package com.nuvio.app.features.shares

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.BackendAuth
import com.nuvio.app.core.network.PrivateBackend
import com.nuvio.app.features.addons.encodeAddonPathSegment
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Thin client for the backend's "Recommend to..." endpoints (`api/routes/shares.py` per the
 * parallel-swinging-flurry plan). Same auth + transport idiom as [com.nuvio.app.features.streams.CatalogDownloadApi] /
 * [com.nuvio.app.features.details.RatingService]: Supabase Bearer via [BackendAuth.authHeadersFor]
 * + `X-Profile-Id` so the backend resolves a profile-level `te_user_id`, sent via [httpRequestRaw]
 * so non-2xx responses (notably 403 on a not-yet-allowed `POST /shares`) are inspectable rather
 * than thrown.
 *
 * All calls are best-effort: network/parse failures return an empty/false result rather than
 * throwing, matching every other backend client in this codebase.
 */
object SharesApi {
    private val log = Logger.withTag("SharesApi")
    private val json = Json { ignoreUnknownKeys = true }

    private fun sharesUrl(path: String): String =
        "${PrivateBackend.baseUrl}/shares$path"

    private fun authHeaders(url: String): Map<String, String>? {
        val base = BackendAuth.authHeadersFor(url)
        if (!base.containsKey("Authorization")) return null
        return base + mapOf(
            "X-Profile-Id" to ProfileRepository.activeProfileId.toString(),
            "Accept" to "application/json",
        )
    }

    /**
     * Every list-returning endpoint here (`roster`, `incoming`, `granted`, `inbox`, and the
     * assumed `mine`) wraps its array in a response object (e.g. `{"roster": [...]}`,
     * `{"inbox": [...], "count": N}`) rather than returning a bare JSON array — confirmed
     * against the live `api/routes/shares.py` handlers. Accepts either shape defensively: a
     * bare array passes through, an object unwraps the first candidate key that holds an
     * array (falling back to the first array-valued field of any name).
     */
    private fun extractArray(element: JsonElement, vararg wrapperKeys: String): JsonArray =
        runCatching {
            when (element) {
                is JsonArray -> element
                is JsonObject -> {
                    wrapperKeys.firstNotNullOfOrNull { key -> element[key] as? JsonArray }
                        ?: element.values.filterIsInstance<JsonArray>().firstOrNull()
                        ?: JsonArray(emptyList())
                }
                else -> JsonArray(emptyList())
            }
        }.getOrDefault(JsonArray(emptyList()))

    private suspend fun getJson(url: String): JsonElement? {
        val headers = authHeaders(url) ?: return null
        return runCatching {
            val response = httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
            if (response.status !in 200..299) {
                log.w { "GET $url failed code=${response.status}" }
                null
            } else {
                json.parseToJsonElement(response.body)
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
            log.w(err) { "GET $url threw" }
        }.getOrNull()
    }

    /** Returns true on 2xx, false on anything else (including no session). */
    private suspend fun postJson(url: String, body: String): Boolean {
        val headers = (authHeaders(url) ?: return false) + ("Content-Type" to "application/json")
        return runCatching {
            val response = httpRequestRaw(method = "POST", url = url, headers = headers, body = body)
            response.status in 200..299
        }.onFailure { err ->
            if (err is CancellationException) throw err
            log.w(err) { "POST $url threw" }
        }.getOrDefault(false)
    }

    private suspend fun putJson(url: String, body: String): Boolean {
        val headers = (authHeaders(url) ?: return false) + ("Content-Type" to "application/json")
        return runCatching {
            val response = httpRequestRaw(method = "PUT", url = url, headers = headers, body = body)
            response.status in 200..299
        }.onFailure { err ->
            if (err is CancellationException) throw err
            log.w(err) { "PUT $url threw" }
        }.getOrDefault(false)
    }

    /** `GET /shares/roster` — full known-account roster minus caller + caller's siblings. */
    suspend fun fetchRoster(): List<RosterUserDto> {
        val element = getJson(sharesUrl("/roster")) ?: return emptyList()
        return runCatching {
            extractArray(element, "roster").map { json.decodeFromJsonElement(RosterUserDto.serializer(), it) }
        }.getOrDefault(emptyList())
    }

    /** `POST /shares/permissions/request` body `{"source_id": ...}`. */
    suspend fun requestPermission(sourceId: String): Boolean =
        postJson(sharesUrl("/permissions/request"), """{"source_id":${jsonString(sourceId)}}""")

    /** `GET /shares/permissions/incoming` — people who want to receive from the caller. */
    suspend fun fetchIncomingPermissionRequests(): List<IncomingPermissionRequestDto> {
        val element = getJson(sharesUrl("/permissions/incoming")) ?: return emptyList()
        return runCatching {
            extractArray(element, "incoming").map { json.decodeFromJsonElement(IncomingPermissionRequestDto.serializer(), it) }
        }.getOrDefault(emptyList())
    }

    /** `POST /shares/permissions/{id}/respond` body `{"action":"allow"|"deny"}`. */
    suspend fun respondToPermissionRequest(id: String, allow: Boolean): Boolean =
        postJson(
            sharesUrl("/permissions/${id.encodeSegment()}/respond"),
            """{"action":"${if (allow) "allow" else "deny"}"}""",
        )

    /** `GET /shares/permissions/granted` — valid "Recommend to..." targets (siblings + allowed). */
    suspend fun fetchGrantedSources(): List<GrantedSourceDto> {
        val element = getJson(sharesUrl("/permissions/granted")) ?: return emptyList()
        return runCatching {
            extractArray(element, "granted").map { json.decodeFromJsonElement(GrantedSourceDto.serializer(), it) }
        }.getOrDefault(emptyList())
    }

    /**
     * `GET /shares/permissions/mine` — the caller's own OUTGOING "receive from" rows (requester
     * side), both pending and allowed. NOT in the plan's explicit endpoint list — see
     * [MyPermissionRequestDto] doc comment; assumed to exist for the Settings page.
     */
    suspend fun fetchMyPermissionRequests(): List<MyPermissionRequestDto> {
        val element = getJson(sharesUrl("/permissions/mine")) ?: return emptyList()
        return runCatching {
            extractArray(element, "permissions", "mine").map { json.decodeFromJsonElement(MyPermissionRequestDto.serializer(), it) }
        }.getOrDefault(emptyList())
    }

    /** `PUT /shares/permissions/{id}/alias` body `{"alias": ...}`. */
    suspend fun updateAlias(id: String, alias: String): Boolean =
        putJson(sharesUrl("/permissions/${id.encodeSegment()}/alias"), """{"alias":${jsonString(alias)}}""")

    /**
     * `POST /shares` — recommend [item] to [recipientUserId]. [addonBaseUrl] comes from the
     * source [com.nuvio.app.features.library.LibraryItem] when the long-pressed poster came
     * from the library (MetaPreview itself carries no addon url) — null otherwise.
     */
    suspend fun sendShare(
        recipientUserId: String,
        item: MetaPreview,
        addonBaseUrl: String?,
    ): Boolean {
        val payload = buildMap<String, JsonElement> {
            put("recipient_user_id", JsonPrimitive(recipientUserId))
            put("content_id", JsonPrimitive(item.id))
            put("content_type", JsonPrimitive(item.type))
            put("name", JsonPrimitive(item.name))
            item.poster?.let { put("poster", JsonPrimitive(it)) }
            item.banner?.let { put("background", JsonPrimitive(it)) }
            item.logo?.let { put("logo", JsonPrimitive(it)) }
            item.description?.let { put("description", JsonPrimitive(it)) }
            item.releaseInfo?.let { put("release_info", JsonPrimitive(it)) }
            item.imdbRating?.let { put("imdb_rating", JsonPrimitive(it)) }
            if (item.genres.isNotEmpty()) {
                put("genres", JsonArray(item.genres.map { JsonPrimitive(it) }))
            }
            addonBaseUrl?.let { put("addon_base_url", JsonPrimitive(it)) }
        }
        val body = JsonObject(payload).toString()
        return postJson(sharesUrl(""), body)
    }

    /** `GET /shares/inbox` — pending content shares + incoming permission requests, newest first. */
    suspend fun fetchInbox(): List<InboxItem> {
        val element = getJson(sharesUrl("/inbox")) ?: return emptyList()
        return runCatching {
            extractArray(element, "inbox", "items").mapNotNull { entry ->
                runCatching {
                    json.decodeFromJsonElement(InboxEntryDto.serializer(), entry.jsonObject).toInboxItem()
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /** `POST /shares/{id}/respond` body `{"action":"added"|"dismissed"}`. */
    suspend fun respondToShare(id: String, added: Boolean): Boolean =
        postJson(
            sharesUrl("/${id.encodeSegment()}/respond"),
            """{"action":"${if (added) "added" else "dismissed"}"}""",
        )

    private fun jsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()

    private fun String.encodeSegment(): String = encodeAddonPathSegment()
}
