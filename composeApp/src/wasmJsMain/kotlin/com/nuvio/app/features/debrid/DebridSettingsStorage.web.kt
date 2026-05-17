package com.nuvio.app.features.debrid

import com.nuvio.app.core.platform.WebKeyValueStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncInt
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncInt
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal actual object DebridSettingsStorage {
    private const val namespace = "nuvio_debrid_settings"
    private const val enabledKey = "debrid_enabled"
    private const val torboxApiKeyKey = "debrid_torbox_api_key"
    private const val realDebridApiKeyKey = "debrid_real_debrid_api_key"
    private const val instantPlaybackPreparationLimitKey = "debrid_instant_playback_preparation_limit"
    private const val streamNameTemplateKey = "debrid_stream_name_template"
    private const val streamDescriptionTemplateKey = "debrid_stream_description_template"
    private val syncKeys = listOf(
        enabledKey,
        torboxApiKeyKey,
        realDebridApiKeyKey,
        instantPlaybackPreparationLimitKey,
        streamNameTemplateKey,
        streamDescriptionTemplateKey,
    )

    actual fun loadEnabled(): Boolean? = loadBoolean(enabledKey)

    actual fun saveEnabled(enabled: Boolean) {
        saveBoolean(enabledKey, enabled)
    }

    actual fun loadTorboxApiKey(): String? = loadString(torboxApiKeyKey)

    actual fun saveTorboxApiKey(apiKey: String) {
        saveString(torboxApiKeyKey, apiKey)
    }

    actual fun loadRealDebridApiKey(): String? = loadString(realDebridApiKeyKey)

    actual fun saveRealDebridApiKey(apiKey: String) {
        saveString(realDebridApiKeyKey, apiKey)
    }

    actual fun loadInstantPlaybackPreparationLimit(): Int? =
        WebKeyValueStorage.getInt(namespace, ProfileScopedKey.of(instantPlaybackPreparationLimitKey))

    actual fun saveInstantPlaybackPreparationLimit(limit: Int) {
        WebKeyValueStorage.setInt(namespace, ProfileScopedKey.of(instantPlaybackPreparationLimitKey), limit)
    }

    actual fun loadStreamNameTemplate(): String? = loadString(streamNameTemplateKey)

    actual fun saveStreamNameTemplate(template: String) {
        saveString(streamNameTemplateKey, template)
    }

    actual fun loadStreamDescriptionTemplate(): String? = loadString(streamDescriptionTemplateKey)

    actual fun saveStreamDescriptionTemplate(template: String) {
        saveString(streamDescriptionTemplateKey, template)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadEnabled()?.let { put(enabledKey, encodeSyncBoolean(it)) }
        loadTorboxApiKey()?.let { put(torboxApiKeyKey, encodeSyncString(it)) }
        loadRealDebridApiKey()?.let { put(realDebridApiKeyKey, encodeSyncString(it)) }
        loadInstantPlaybackPreparationLimit()?.let { put(instantPlaybackPreparationLimitKey, encodeSyncInt(it)) }
        loadStreamNameTemplate()?.let { put(streamNameTemplateKey, encodeSyncString(it)) }
        loadStreamDescriptionTemplate()?.let { put(streamDescriptionTemplateKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { WebKeyValueStorage.remove(namespace, ProfileScopedKey.of(it)) }
        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
        payload.decodeSyncString(torboxApiKeyKey)?.let(::saveTorboxApiKey)
        payload.decodeSyncString(realDebridApiKeyKey)?.let(::saveRealDebridApiKey)
        payload.decodeSyncInt(instantPlaybackPreparationLimitKey)?.let(::saveInstantPlaybackPreparationLimit)
        payload.decodeSyncString(streamNameTemplateKey)?.let(::saveStreamNameTemplate)
        payload.decodeSyncString(streamDescriptionTemplateKey)?.let(::saveStreamDescriptionTemplate)
    }

    private fun loadBoolean(key: String): Boolean? =
        WebKeyValueStorage.getBoolean(namespace, ProfileScopedKey.of(key))

    private fun saveBoolean(key: String, value: Boolean) {
        WebKeyValueStorage.setBoolean(namespace, ProfileScopedKey.of(key), value)
    }

    private fun loadString(key: String): String? =
        WebKeyValueStorage.getString(namespace, ProfileScopedKey.of(key))

    private fun saveString(key: String, value: String) {
        WebKeyValueStorage.setString(namespace, ProfileScopedKey.of(key), value)
    }
}
