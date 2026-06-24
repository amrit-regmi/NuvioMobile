package com.nuvio.app.core.network

import android.content.Context
import android.content.SharedPreferences

internal actual object PrivateBackendUrlStorage {
    private const val preferencesName = "nuvio_private_backend"
    private const val overrideKey = "fastapi_base_url_override"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadOverride(): String? =
        preferences?.getString(overrideKey, null)?.trim()?.takeIf { it.isNotBlank() }

    actual fun saveOverride(url: String?) {
        val editor = preferences?.edit() ?: return
        if (url.isNullOrBlank()) {
            editor.remove(overrideKey)
        } else {
            editor.putString(overrideKey, url.trim())
        }
        editor.apply()
    }
}
