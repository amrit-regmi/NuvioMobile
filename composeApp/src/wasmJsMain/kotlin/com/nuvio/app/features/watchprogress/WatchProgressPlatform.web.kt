package com.nuvio.app.features.watchprogress

import com.nuvio.app.core.platform.WebKeyValueStorage
import com.nuvio.app.core.platform.webNowEpochMs
import com.nuvio.app.core.platform.webTodayIsoDate
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object ContinueWatchingEnrichmentStorage {
    private const val namespace = "nuvio_cw_enrichment"

    actual fun loadPayload(key: String): String? =
        WebKeyValueStorage.getString(namespace, key)

    actual fun savePayload(key: String, payload: String) {
        WebKeyValueStorage.setString(namespace, key, payload)
    }
}

internal actual object ContinueWatchingPreferencesStorage {
    private const val namespace = "nuvio_continue_watching_preferences"
    private const val payloadKey = "continue_watching_preferences_payload"

    actual fun loadPayload(): String? =
        WebKeyValueStorage.getString(namespace, ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        WebKeyValueStorage.setString(namespace, ProfileScopedKey.of(payloadKey), payload)
    }
}

actual object CurrentDateProvider {
    actual fun todayIsoDate(): String = webTodayIsoDate()
}

internal actual object ResumePromptStorage {
    private const val namespace = "nuvio_resume_prompt"
    private const val wasInPlayerKey = "was_in_player"
    private const val lastPlayerVideoIdKey = "last_player_video_id"

    actual fun loadWasInPlayer(): Boolean =
        WebKeyValueStorage.getBoolean(namespace, ProfileScopedKey.of(wasInPlayerKey)) ?: false

    actual fun saveWasInPlayer(value: Boolean) {
        WebKeyValueStorage.setBoolean(namespace, ProfileScopedKey.of(wasInPlayerKey), value)
    }

    actual fun loadLastPlayerVideoId(): String? =
        WebKeyValueStorage.getString(namespace, ProfileScopedKey.of(lastPlayerVideoIdKey))

    actual fun saveLastPlayerVideoId(videoId: String?) {
        val scopedKey = ProfileScopedKey.of(lastPlayerVideoIdKey)
        if (videoId == null) {
            WebKeyValueStorage.remove(namespace, scopedKey)
        } else {
            WebKeyValueStorage.setString(namespace, scopedKey, videoId)
        }
    }
}

internal actual object WatchProgressClock {
    actual fun nowEpochMs(): Long = webNowEpochMs()
}

internal actual object WatchProgressStorage {
    private const val namespace = "nuvio_watch_progress"

    actual fun loadPayload(profileId: Int): String? =
        WebKeyValueStorage.getString(namespace, payloadKey(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        WebKeyValueStorage.setString(namespace, payloadKey(profileId), payload)
    }

    private fun payloadKey(profileId: Int): String = "watch_progress_payload_$profileId"
}
