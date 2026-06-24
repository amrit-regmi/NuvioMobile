package com.nuvio.app.core.network

import platform.Foundation.NSUserDefaults

internal actual object PrivateBackendUrlStorage {
    private const val overrideKey = "fastapi_base_url_override"

    actual fun loadOverride(): String? =
        NSUserDefaults.standardUserDefaults
            .stringForKey(overrideKey)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    actual fun saveOverride(url: String?) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (url.isNullOrBlank()) {
            defaults.removeObjectForKey(overrideKey)
        } else {
            defaults.setObject(url.trim(), forKey = overrideKey)
        }
    }
}
