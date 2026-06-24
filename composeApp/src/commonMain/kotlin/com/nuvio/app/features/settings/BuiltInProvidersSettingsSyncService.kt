package com.nuvio.app.features.settings

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Syncs the built-in provider toggles (stream + subtitle) to the SHARED
 * `nuvio_profile_settings` Supabase row — the same row + keys the TV app uses, so a user's
 * stream/subtitle provider choice stays consistent across TV + mobile.
 *
 *  - Stream provider toggle ↔ `settings_json.streamProvider`
 *    (ON → "builtin" preserving an existing "own"; OFF → "addons"; read: != "addons").
 *    This is exactly the TV app's mapping.
 *  - Subtitle provider toggle ↔ `settings_json.useBuiltinSubtitles` (mobile-introduced key;
 *    the TV app gates subtitles transitively via the catalog toggle — see report).
 *
 * The push is a READ-MERGE-WRITE because the profile-settings push RPC replaces the whole
 * `settings_json` (no server-side merge), so we must preserve every sibling key the TV app
 * (or other mobile sync paths) wrote.
 */
object BuiltInProvidersSettingsSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("BuiltInProvidersSync")
    private val json = Json { ignoreUnknownKeys = true }

    private const val STREAM_PROVIDER_KEY = "streamProvider"
    private const val USE_BUILTIN_SUBTITLES_KEY = "useBuiltinSubtitles"
    private const val PUSH_DEBOUNCE_MS = 600L

    @Volatile
    private var isApplyingRemote = false
    private var pushJob: Job? = null

    suspend fun pullFromServer(profileId: Int) {
        if (!isSignedIn()) return
        runCatching {
            val remote = fetchSettingsJson(profileId) ?: return
            val streamProvider = (remote[STREAM_PROVIDER_KEY] as? JsonPrimitive)?.contentOrNull
            val streamEnabled = streamProvider?.let { !it.equals("addons", ignoreCase = true) }
            val subtitleEnabled = (remote[USE_BUILTIN_SUBTITLES_KEY] as? JsonPrimitive)?.booleanOrNull
            isApplyingRemote = true
            try {
                BuiltInProvidersSettingsRepository.applyFromRemote(
                    streamEnabled = streamEnabled,
                    subtitleEnabled = subtitleEnabled,
                )
            } finally {
                isApplyingRemote = false
            }
        }.onFailure { log.e(it) { "pullFromServer FAILED" } }
    }

    fun triggerPush() {
        if (isApplyingRemote || !isSignedIn()) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            if (!isSignedIn()) return@launch
            pushToRemote(ProfileRepository.activeProfileId)
        }
    }

    private suspend fun pushToRemote(profileId: Int) {
        runCatching {
            val existing = fetchSettingsJson(profileId) ?: JsonObject(emptyMap())
            val streamEnabled = BuiltInProvidersSettingsRepository.isStreamProviderEnabled()
            val subtitleEnabled = BuiltInProvidersSettingsRepository.isSubtitleProviderEnabled()

            // Preserve an existing "own" stream provider when re-enabling (matches TV).
            val existingStreamProvider =
                (existing[STREAM_PROVIDER_KEY] as? JsonPrimitive)?.contentOrNull
            val nextStreamProvider = when {
                !streamEnabled -> "addons"
                existingStreamProvider.equals("own", ignoreCase = true) -> "own"
                else -> "builtin"
            }

            val merged = buildJsonObject {
                existing.forEach { (key, value) -> put(key, value) }
                put(STREAM_PROVIDER_KEY, nextStreamProvider)
                put(USE_BUILTIN_SUBTITLES_KEY, subtitleEnabled)
            }

            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_settings_json", merged)
            }
            SupabaseProvider.client.postgrest.rpc("sync_push_profile_settings_blob", params)
            log.d { "pushToRemote(profileId=$profileId) success" }
        }.onFailure { log.e(it) { "pushToRemote FAILED" } }
    }

    private suspend fun fetchSettingsJson(profileId: Int): JsonObject? {
        val params = buildJsonObject { put("p_profile_id", profileId) }
        val result = SupabaseProvider.client.postgrest.rpc("sync_pull_profile_settings_blob", params)
        return result.decodeList<ProfileSettingsRow>().firstOrNull()?.settingsJson
    }

    private fun isSignedIn(): Boolean {
        val state = AuthRepository.state.value
        return state is AuthState.Authenticated && !state.isAnonymous
    }

    @Serializable
    private data class ProfileSettingsRow(
        @SerialName("profile_id") val profileId: Int = 1,
        @SerialName("settings_json") val settingsJson: JsonObject? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
    )
}
