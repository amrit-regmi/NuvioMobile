package com.nuvio.app.features.shares

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client-side models for the "Recommend to..." feature (see parallel-swinging-flurry plan,
 * Feature 2). All `*_id` values here are profile-level `te_user_id` strings — opaque to the
 * client, resolved/compared server-side.
 */

/** One entry from `GET /shares/roster` — a known account/profile NOT already reachable. */
@Serializable
data class RosterUserDto(
    @SerialName("user_id") val userId: String,
    @SerialName("name") val name: String? = null,
)

/** One entry from `GET /shares/permissions/granted` — a valid "Recommend to..." target. */
@Serializable
data class GrantedSourceDto(
    @SerialName("user_id") val userId: String,
    @SerialName("name") val name: String? = null,
    @SerialName("sibling") val isSibling: Boolean = false,
)

/** One pending request where the caller is `source_id` ("X wants to receive from you"). */
@Serializable
data class IncomingPermissionRequestDto(
    @SerialName("id") val id: String,
    @SerialName("requester_id") val requesterId: String,
    @SerialName("requester_name") val requesterName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * One of the caller's own OUTGOING "receive from" requests/grants (caller is `requester_id`).
 *
 * NOTE (deviation from the plan): the plan's endpoint list has no GET for "my own permission
 * rows as requester" — only `/shares/permissions/incoming` (source-side) and
 * `/shares/permissions/granted` (source-side, populates the SENDER picker). The Settings page
 * ("list of allowed sources + alias" + "Pending outgoing requests") needs the requester-side
 * view, so this client assumes a new `GET /shares/permissions/mine` endpoint, following the
 * same `/shares/permissions/*` naming convention. Flagged for the backend implementer.
 */
@Serializable
data class MyPermissionRequestDto(
    @SerialName("id") val id: String,
    @SerialName("source_id") val sourceId: String,
    @SerialName("source_name") val sourceName: String? = null,
    @SerialName("alias") val alias: String? = null,
    @SerialName("status") val status: String = "pending", // pending | allowed | denied
    @SerialName("created_at") val createdAt: String? = null,
)

/** One `GET /shares/inbox` row — either a content share or an incoming permission request. */
@Serializable
data class InboxEntryDto(
    @SerialName("type") val type: String, // "share" | "permission_request"
    @SerialName("id") val id: String,
    @SerialName("created_at") val createdAt: String? = null,
    // type == "share"
    @SerialName("sender_user_id") val senderId: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("content_id") val contentId: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("poster") val poster: String? = null,
    @SerialName("background") val background: String? = null,
    @SerialName("logo") val logo: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("release_info") val releaseInfo: String? = null,
    @SerialName("imdb_rating") val imdbRating: String? = null,
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("addon_base_url") val addonBaseUrl: String? = null,
    // type == "permission_request"
    @SerialName("requester_id") val requesterId: String? = null,
    @SerialName("requester_name") val requesterName: String? = null,
)

/** Parsed, UI-friendly form of [InboxEntryDto]. */
sealed interface InboxItem {
    val id: String
    val createdAt: String?

    data class Share(
        override val id: String,
        override val createdAt: String?,
        val senderId: String,
        val senderName: String,
        val contentId: String,
        val contentType: String,
        val name: String,
        val poster: String?,
        val background: String?,
        val logo: String?,
        val description: String?,
        val releaseInfo: String?,
        val imdbRating: String?,
        val genres: List<String>,
        val addonBaseUrl: String?,
    ) : InboxItem

    data class PermissionRequest(
        override val id: String,
        override val createdAt: String?,
        val requesterId: String,
        val requesterName: String,
    ) : InboxItem
}

fun InboxEntryDto.toInboxItem(): InboxItem? = when (type) {
    "share" -> {
        val cid = contentId ?: return null
        val ctype = contentType ?: return null
        InboxItem.Share(
            id = id,
            createdAt = createdAt,
            senderId = senderId.orEmpty(),
            senderName = senderName?.takeIf { it.isNotBlank() } ?: senderId.orEmpty(),
            contentId = cid,
            contentType = ctype,
            name = name.orEmpty(),
            poster = poster,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = addonBaseUrl,
        )
    }
    "permission_request" -> {
        val rid = requesterId ?: return null
        InboxItem.PermissionRequest(
            id = id,
            createdAt = createdAt,
            requesterId = rid,
            requesterName = requesterName?.takeIf { it.isNotBlank() } ?: rid,
        )
    }
    else -> null
}
