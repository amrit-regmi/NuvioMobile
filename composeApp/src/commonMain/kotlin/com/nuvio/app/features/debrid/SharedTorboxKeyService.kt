package com.nuvio.app.features.debrid

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.BackendAuth
import com.nuvio.app.core.network.PrivateBackend
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.streams.epochMs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val CACHE_TTL_MS = 30L * 60L * 1000L

@Serializable
private data class TorboxKeyResponse(
    val service: String? = null,
    val key: String? = null,
)

object SharedTorboxKeyService {
    private val log = Logger.withTag("SharedTorboxKeyService")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var cachedKey: String? = null
    private var cachedAtMs: Long = 0L

    fun isConfigured(): Boolean = PrivateBackend.baseUrl.isNotBlank()

    private fun isAuthenticated(): Boolean {
        val state = AuthRepository.state.value
        return state is AuthState.Authenticated && !state.isAnonymous
    }

    suspend fun getKey(): String? = mutex.withLock {
        val now = epochMs()
        val age = now - cachedAtMs
        if (cachedKey != null && age in 0..CACHE_TTL_MS) {
            return@withLock cachedKey
        }
        if (!isAuthenticated()) {
            log.d { "Not authenticated — shared TorBox key disabled" }
            return@withLock null
        }
        val url = "${PrivateBackend.catalogAddonUrl}/torbox-key"
        return@withLock try {
            val body = httpGetTextWithHeaders(url, BackendAuth.authHeadersFor(url))
            val parsed = json.decodeFromString<TorboxKeyResponse>(body)
            val key = parsed.key?.trim()?.takeIf { it.isNotBlank() }
            if (key != null) {
                cachedKey = key
                cachedAtMs = now
                log.d { "Fetched shared TorBox key from backend" }
            } else {
                log.w { "Backend returned empty TorBox key" }
            }
            key
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Failed to fetch shared TorBox key" }
            null
        }
    }

    fun invalidate() {
        cachedAtMs = 0L
        cachedKey = null
    }
}
