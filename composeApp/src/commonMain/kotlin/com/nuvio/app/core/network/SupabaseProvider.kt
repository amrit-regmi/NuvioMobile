package com.nuvio.app.core.network

import com.nuvio.app.core.build.AppVersionConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders

object SupabaseProvider {
    private data class ClientHolder(
        val backend: SyncBackendConfig,
        val client: SupabaseClient,
    )

    // Lock protects holder against concurrent creation from multiple Dispatchers.Default
    // coroutines all racing to call SupabaseProvider.client before the first client is stored,
    // which would cause the Supabase library to emit "SupabaseClient created!" 3+ times.
    private val holderLock = Any()
    @Volatile private var holder: ClientHolder? = null

    val selectedBackend: SyncBackendConfig
        get() = SyncBackendRepository.selectedBackend

    @OptIn(SupabaseInternal::class)
    val client: SupabaseClient
        get() = clientFor(selectedBackend)

    fun rebuildClient() {
        synchronized(holderLock) {
            holder = null
        }
    }

    @OptIn(SupabaseInternal::class)
    private fun clientFor(config: SyncBackendConfig): SupabaseClient {
        // Fast path: already have a compatible client.
        holder
            ?.takeIf { it.backend.hasSameConnectionIdentity(config) }
            ?.let { return it.client }

        // Slow path: create under lock to ensure exactly one client is ever created
        // per connection identity, even when multiple coroutines race at startup.
        synchronized(holderLock) {
            // Re-check inside the lock (double-checked locking).
            holder
                ?.takeIf { it.backend.hasSameConnectionIdentity(config) }
                ?.let { return it.client }

            val userAgent = "NuvioMobile/${AppVersionConfig.VERSION_NAME.ifBlank { "dev" }}"
            val nextClient = createSupabaseClient(
                supabaseUrl = config.normalizedSupabaseUrl,
                supabaseKey = config.anonKey,
            ) {
                httpConfig {
                    defaultRequest {
                        headers.append(HttpHeaders.UserAgent, userAgent)
                    }
                }
                install(Auth)
                install(Postgrest)
                install(Functions)
            }
            holder = ClientHolder(backend = config, client = nextClient)
            return nextClient
        }
    }
}
