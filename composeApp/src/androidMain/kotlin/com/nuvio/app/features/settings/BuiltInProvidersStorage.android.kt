package com.nuvio.app.features.settings

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

actual object BuiltInProvidersStorage {
    private const val preferencesName = "nuvio_builtin_providers"
    private const val streamProviderEnabledKey = "builtin_stream_provider_enabled"
    private const val subtitleProviderEnabledKey = "builtin_subtitle_provider_enabled"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadStreamProviderEnabled(): Boolean? = loadBoolean(streamProviderEnabledKey)

    actual fun saveStreamProviderEnabled(enabled: Boolean) =
        saveBoolean(streamProviderEnabledKey, enabled)

    actual fun loadSubtitleProviderEnabled(): Boolean? = loadBoolean(subtitleProviderEnabledKey)

    actual fun saveSubtitleProviderEnabled(enabled: Boolean) =
        saveBoolean(subtitleProviderEnabledKey, enabled)

    private fun loadBoolean(baseKey: String): Boolean? {
        val prefs = preferences ?: return null
        val key = ProfileScopedKey.of(baseKey)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    private fun saveBoolean(baseKey: String, value: Boolean) {
        preferences?.edit()?.putBoolean(ProfileScopedKey.of(baseKey), value)?.apply()
    }
}
