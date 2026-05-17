package com.nuvio.app.features.settings

import com.nuvio.app.core.platform.WebKeyValueStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal actual object ThemeSettingsStorage {
    private const val namespace = "nuvio_theme_settings"
    private const val selectedThemeKey = "selected_theme"
    private const val amoledEnabledKey = "amoled_enabled"
    private const val liquidGlassNativeTabBarEnabledKey = "liquid_glass_native_tab_bar_enabled"
    private const val selectedAppLanguageKey = "selected_app_language"
    private val profileScopedSyncKeys = listOf(
        selectedThemeKey,
        amoledEnabledKey,
        liquidGlassNativeTabBarEnabledKey,
    )

    actual fun loadSelectedTheme(): String? =
        WebKeyValueStorage.getString(namespace, ProfileScopedKey.of(selectedThemeKey))

    actual fun saveSelectedTheme(themeName: String) {
        WebKeyValueStorage.setString(namespace, ProfileScopedKey.of(selectedThemeKey), themeName)
    }

    actual fun loadAmoledEnabled(): Boolean? =
        WebKeyValueStorage.getBoolean(namespace, ProfileScopedKey.of(amoledEnabledKey))

    actual fun saveAmoledEnabled(enabled: Boolean) {
        WebKeyValueStorage.setBoolean(namespace, ProfileScopedKey.of(amoledEnabledKey), enabled)
    }

    actual fun loadLiquidGlassNativeTabBarEnabled(): Boolean? =
        WebKeyValueStorage.getBoolean(namespace, ProfileScopedKey.of(liquidGlassNativeTabBarEnabledKey))

    actual fun saveLiquidGlassNativeTabBarEnabled(enabled: Boolean) {
        WebKeyValueStorage.setBoolean(namespace, ProfileScopedKey.of(liquidGlassNativeTabBarEnabledKey), enabled)
    }

    actual fun loadSelectedAppLanguage(): String? {
        val value = WebKeyValueStorage.getString(namespace, selectedAppLanguageKey)
        if (value != null) return value
        val legacy = WebKeyValueStorage.getString(namespace, ProfileScopedKey.of(selectedAppLanguageKey))
        if (legacy != null) saveSelectedAppLanguage(legacy)
        return legacy
    }

    actual fun saveSelectedAppLanguage(languageCode: String) {
        WebKeyValueStorage.setString(namespace, selectedAppLanguageKey, languageCode)
    }

    actual fun applySelectedAppLanguage(languageCode: String) = Unit

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadSelectedTheme()?.let { put(selectedThemeKey, encodeSyncString(it)) }
        loadAmoledEnabled()?.let { put(amoledEnabledKey, encodeSyncBoolean(it)) }
        loadLiquidGlassNativeTabBarEnabled()?.let { put(liquidGlassNativeTabBarEnabledKey, encodeSyncBoolean(it)) }
        loadSelectedAppLanguage()?.let { put(selectedAppLanguageKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        profileScopedSyncKeys.forEach { WebKeyValueStorage.remove(namespace, ProfileScopedKey.of(it)) }
        WebKeyValueStorage.remove(namespace, selectedAppLanguageKey)

        payload.decodeSyncString(selectedThemeKey)?.let(::saveSelectedTheme)
        payload.decodeSyncBoolean(amoledEnabledKey)?.let(::saveAmoledEnabled)
        payload.decodeSyncBoolean(liquidGlassNativeTabBarEnabledKey)?.let(::saveLiquidGlassNativeTabBarEnabled)
        payload.decodeSyncString(selectedAppLanguageKey)?.let(::saveSelectedAppLanguage)
        applySelectedAppLanguage(loadSelectedAppLanguage() ?: AppLanguage.ENGLISH.code)
    }
}
