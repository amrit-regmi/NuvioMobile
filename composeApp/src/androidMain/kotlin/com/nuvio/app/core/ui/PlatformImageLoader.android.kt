package com.nuvio.app.core.ui

import android.os.Build
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.nuvio.app.core.network.BackendAuth
import com.nuvio.app.core.network.PrivateBackend
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Bug 2 (private-backend fork): our backend serves posters/backdrops behind auth
 * (`/image/...`, catalog-addon poster/background proxies). Those image requests must
 * carry the Supabase Bearer token for OUR host only — mirroring NuvioTV's
 * `NuvioApplication` Coil loader wired with `RecoAuthInterceptor`.
 *
 * Coil's default network fetcher uses a plain client with no auth, so authenticated
 * poster/backdrop URLs came back 401 and thumbnails rendered dark. We register an
 * explicit OkHttp-backed network fetcher whose client attaches
 * `Authorization: Bearer <jwt>` via [BackendAuth.authHeadersFor] — host-scoped to
 * [PrivateBackend.host], so TMDB and every other image host are left untouched (no token
 * leak). An explicitly-registered fetcher in `components { }` takes precedence over the
 * ServiceLoader-registered default Ktor fetcher.
 */
private val backendImageAuthInterceptor = Interceptor { chain ->
    val request = chain.request()
    val url = request.url.toString()
    if (PrivateBackend.isBackendUrl(url) && request.header("Authorization") == null) {
        BackendAuth.authHeadersFor(url)["Authorization"]?.let { token ->
            return@Interceptor chain.proceed(
                request.newBuilder()
                    .header("Authorization", token)
                    .build(),
            )
        }
    }
    chain.proceed(request)
}

private val imageOkHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(backendImageAuthInterceptor)
        .build()
}

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    components {
        if (Build.VERSION.SDK_INT >= 28) {
            add(AnimatedImageDecoder.Factory())
        } else {
            add(GifDecoder.Factory())
        }
        add(OkHttpNetworkFetcherFactory(callFactory = { imageOkHttpClient }))
    }
