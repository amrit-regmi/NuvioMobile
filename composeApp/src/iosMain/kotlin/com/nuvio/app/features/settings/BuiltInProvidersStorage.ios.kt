package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

actual object BuiltInProvidersStorage {
    private const val streamProviderEnabledKey = "builtin_stream_provider_enabled"
    private const val subtitleProviderEnabledKey = "builtin_subtitle_provider_enabled"

    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    actual fun loadStreamProviderEnabled(): Boolean? = loadBoolean(streamProviderEnabledKey)

    actual fun saveStreamProviderEnabled(enabled: Boolean) =
        saveBoolean(streamProviderEnabledKey, enabled)

    actual fun loadSubtitleProviderEnabled(): Boolean? = loadBoolean(subtitleProviderEnabledKey)

    actual fun saveSubtitleProviderEnabled(enabled: Boolean) =
        saveBoolean(subtitleProviderEnabledKey, enabled)

    private fun loadBoolean(baseKey: String): Boolean? {
        val key = ProfileScopedKey.of(baseKey)
        return if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
    }

    private fun saveBoolean(baseKey: String, value: Boolean) {
        defaults.setBool(value, forKey = ProfileScopedKey.of(baseKey))
    }
}
