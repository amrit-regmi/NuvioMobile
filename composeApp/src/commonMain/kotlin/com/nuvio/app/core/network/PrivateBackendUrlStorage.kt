package com.nuvio.app.core.network

/**
 * Persists the optional user override for the private FastAPI backend base URL.
 * A null value means "use the baked-in default" ([PrivateBackendConfig.FASTAPI_BASE_URL]).
 */
internal expect object PrivateBackendUrlStorage {
    fun loadOverride(): String?
    fun saveOverride(url: String?)
}
