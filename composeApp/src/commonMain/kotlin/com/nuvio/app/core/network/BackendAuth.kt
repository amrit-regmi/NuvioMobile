package com.nuvio.app.core.network

import co.touchlab.kermit.Logger
import io.github.jan.supabase.auth.auth

/**
 * Bridges the live Supabase session access token onto our private FastAPI backend.
 *
 * In private mode the backend (`/catalog-addon/...`, `/reco/...`, `/meta`, `/stream`, …)
 * requires a valid USER Bearer token — the same Supabase JWT the dashboard uses.
 * This mirrors NuvioTV's `RecoAuthInterceptor`: attach `Authorization: Bearer <jwt>`
 * to every request whose host matches [PrivateBackend.host], and nothing else.
 *
 * Content fetchers should pass the result of [authHeadersFor] into the platform
 * HTTP layer (`httpGetTextWithHeaders` / `httpPostJsonWithHeaders`).
 */
object BackendAuth {
    private val log = Logger.withTag("BackendAuth")

    /** Current Supabase session access token, or null if not signed in. */
    fun currentAccessToken(): String? =
        runCatching { SupabaseProvider.client.auth.currentAccessTokenOrNull() }
            .onFailure { log.w(it) { "Unable to read Supabase access token" } }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Headers to attach for [url]. Returns the Bearer header ONLY when [url] targets our
     * backend host (host-matched, like RecoAuthInterceptor) AND a session token exists.
     * For any other host (e.g. a stream CDN), returns empty so we never leak the token.
     */
    fun authHeadersFor(url: String): Map<String, String> {
        if (!PrivateBackend.isBackendUrl(url)) return emptyMap()
        val token = currentAccessToken() ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }
}
