package com.nuvio.app.core.network

/**
 * Single source of truth for our private FastAPI backend (taste-engine, served at
 * hamrocinema.regmig.com). Mirrors NuvioTV's `RecoBackend`.
 *
 * The built-in host is [PrivateBackendConfig.FASTAPI_BASE_URL] (baked at build time),
 * but it can be overridden at runtime by a persisted user value
 * ([PrivateBackendUrlStorage]). The effective base = (user override ?? built-in default).
 *
 * Everything content-related derives from [baseUrl]:
 *  - the built-in Stremio catalog-addon ([catalogAddonManifestUrl]) used for
 *    home rows / catalogs / meta / streams / subtitles,
 *  - the reco endpoints,
 *  - the TMDB + image proxies,
 *  - and the host-match used to decide whether to attach the Supabase Bearer token.
 *
 * The override is loaded ONCE at startup ([init]); changing it mid-session does not
 * hot-swap (the user logs out + back in), avoiding tearing a live session across
 * two different backends.
 *
 * Do NOT hardcode the backend host anywhere else — reference these members.
 */
object PrivateBackend {

    /** Effective base URL of the FastAPI backend (no trailing slash). */
    var baseUrl: String = normalize(PrivateBackendConfig.FASTAPI_BASE_URL)
        private set

    /** Bare host of [baseUrl], used for Bearer-token host matching. */
    var host: String = hostOf(baseUrl)
        private set

    /** Canonical built-in Stremio catalog-addon manifest URL served by the backend. */
    val catalogAddonManifestUrl: String
        get() = "$baseUrl/catalog-addon/manifest.json"

    /** Catalog-addon transport base (no `/manifest.json`, no trailing slash). */
    val catalogAddonUrl: String
        get() = "$baseUrl/catalog-addon"

    /** Drop-in base for the backend's TMDB proxy (injects the server-side api_key). */
    val tmdbProxyBaseUrl: String
        get() = "$baseUrl/tmdb/3/"

    /** Reco endpoints base. */
    val recoBaseUrl: String
        get() = "$baseUrl/reco"

    /**
     * Applies the persisted user override (if any) over the built-in default. Call ONCE,
     * synchronously, at the very start of app launch — before any content client is built —
     * so every derived value reflects the override.
     */
    fun init() {
        val override = PrivateBackendUrlStorage.loadOverride()
            ?.let { normalizeOrNull(it) }
        val effective = override ?: normalize(PrivateBackendConfig.FASTAPI_BASE_URL)
        baseUrl = effective
        host = hostOf(effective)
    }

    /** Persists a user override (or clears it when null/blank) for the next launch. */
    fun setOverride(url: String?) {
        val normalized = url?.let { normalizeOrNull(it) }
        PrivateBackendUrlStorage.saveOverride(normalized)
    }

    /** True if [url]'s host matches our backend host (so the Bearer token applies). */
    fun isBackendUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return hostOf(raw).equals(host, ignoreCase = true)
    }

    private fun hostOf(url: String): String = url
        .substringAfter("://")
        .substringBefore("/")
        .substringBefore(":")

    private fun normalize(url: String): String = url.trim().trimEnd('/')

    private fun normalizeOrNull(url: String): String? {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        return trimmed
    }
}
