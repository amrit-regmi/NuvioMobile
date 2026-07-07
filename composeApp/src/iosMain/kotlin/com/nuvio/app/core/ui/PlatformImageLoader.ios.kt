package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.nuvio.app.core.network.BackendAuth
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder

/**
 * Bug 2 (private-backend fork), iOS half: the previous actual was a no-op (`= this`), so on
 * iOS the Coil ImageLoader had NO network fetcher registered at all. On Android, Coil's
 * ServiceLoader auto-registers a default network fetcher; on Kotlin/Native there is no
 * ServiceLoader, so nothing gets wired unless we add it explicitly. Result: catalog *metadata*
 * loaded (that goes through the app's own Darwin Ktor client) while every remote *poster/backdrop*
 * silently failed — the "logged in but no images" symptom.
 *
 * We register an explicit Ktor(Darwin)-backed network fetcher. Its client also attaches the
 * Supabase Bearer for OUR backend host only (via [BackendAuth.authHeadersFor], which is
 * host-matched to PrivateBackend.host), because our backend serves posters/backdrops behind auth
 * (`/image/...`, catalog-addon poster/background proxies). TMDB and every other image host are
 * left untouched, so the token never leaks. This mirrors the Android actual's authed OkHttp
 * fetcher and NuvioTV's RecoAuthInterceptor.
 */
private val backendImageAuth = createClientPlugin("BackendImageAuth") {
    onRequest { request: HttpRequestBuilder, _ ->
        val url = request.url.buildString()
        BackendAuth.authHeadersFor(url).forEach { (name, value) ->
            if (!request.headers.contains(name)) request.headers.append(name, value)
        }
    }
}

private val imageHttpClient: HttpClient by lazy {
    HttpClient(Darwin) {
        followRedirects = true
        install(backendImageAuth)
    }
}

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    components {
        add(KtorNetworkFetcherFactory(httpClient = { imageHttpClient }))
    }
