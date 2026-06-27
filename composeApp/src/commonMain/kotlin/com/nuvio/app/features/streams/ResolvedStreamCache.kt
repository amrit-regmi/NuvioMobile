package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.features.debrid.DebridProviders
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Client-side cache of debrid-resolved direct CDN urls produced during background warm
 * ([StreamWarmer]). Mirrors NuvioTV's StreamWarmer.resolvedUrlCache.
 *
 * Keyed by the stream's stable debrid identity ([StreamItem.resolvedStreamCacheKey]) so that
 * the play path can serve a pre-resolved url INSTANTLY instead of doing a fresh TorBox
 * round-trip (createtorrent → mylist → requestdl) on every play, which on mobile was the
 * cause of slow playback and "could not open link" timeouts.
 *
 * TorBox CDN urls expire in ~2 hours; a 60-min TTL guarantees we never serve an expired link.
 * The cache is best-effort: entries beyond TTL are evicted on read, and the play path always
 * falls back to a fresh resolve when [get] returns null.
 *
 * Thread-safe via atomicfu's [SynchronizedObject] (same pattern as WatchProgressRepository).
 * All public reads/writes are non-suspending and fast (in-memory map ops).
 */
object ResolvedStreamCache {
    private val log = Logger.withTag("ResolvedStream")

    /** Serve nothing older than 60 minutes — TorBox CDN urls live ~2h. */
    private const val TTL_MS = 60L * 60L * 1000L
    private const val MAX_ENTRIES = 200

    private data class Entry(
        val url: String,
        val filename: String?,
        val videoSize: Long?,
        val resolvedAt: Long,
    )

    private val lock = SynchronizedObject()
    private val cache = LinkedHashMap<String, Entry>()

    /**
     * Optional listener notified (with the resolved key) whenever a url is cached — used by the
     * stream list to promote a warm-resolved stream to a ready / "Tier-1" state in the UI before the
     * user taps Play. Best-effort, single observer (the active stream screen); the consumer guards on
     * its request token so a stale screen no-ops. Invoked OUTSIDE [lock] to avoid re-entrancy.
     */
    @Volatile
    private var onResolved: ((String) -> Unit)? = null

    fun setOnResolved(listener: ((String) -> Unit)?) {
        onResolved = listener
    }

    /**
     * Returns a fresh resolved CDN url for [key], or null if absent/expired.
     * Expired entries are evicted on read.
     */
    fun get(key: String?): String? {
        val k = key ?: return null
        val now = epochMs()
        return synchronized(lock) {
            val entry = cache[k] ?: return@synchronized null
            if (now - entry.resolvedAt in 0..TTL_MS) {
                entry.url
            } else {
                cache.remove(k)
                null
            }
        }
    }

    /** Convenience: resolve directly from a [StreamItem]. */
    fun get(stream: StreamItem, season: Int?, episode: Int?): String? =
        get(stream.resolvedStreamCacheKey(season, episode))

    /** Stores [url] for [key] with the current timestamp, evicting the eldest if over capacity. */
    fun put(key: String?, url: String, filename: String? = null, videoSize: Long? = null) {
        val k = key ?: return
        if (url.isBlank()) return
        synchronized(lock) {
            cache[k] = Entry(url = url, filename = filename, videoSize = videoSize, resolvedAt = epochMs())
            while (cache.size > MAX_ENTRIES) {
                val eldest = cache.keys.firstOrNull() ?: break
                cache.remove(eldest)
            }
        }
        log.d { "Cached resolved url key=$k url=${url.take(60)}…" }
        // Notify the active stream list so it can flip the matching stream to a ready/"Tier-1"
        // state. Best-effort, off the lock; consumer guards on its request token.
        runCatching { onResolved?.invoke(k) }
    }

    /** Evicts the entry for [key] (e.g. after a CDN 4xx/5xx during playback). */
    fun evict(key: String?) {
        val k = key ?: return
        synchronized(lock) { cache.remove(k) }
    }

    /** Drops all entries (sign-out — never replay shared-key resolved urls when signed out). */
    fun clear() {
        synchronized(lock) { cache.clear() }
        log.d { "Cleared resolved stream cache" }
    }

    fun isFresh(key: String?): Boolean = get(key) != null

    // --- Single-flight coordinator -------------------------------------------------------------
    //
    // The slowest path on mobile is a COLD TorBox resolve (createtorrent → cache fill → requestdl)
    // which can take MINUTES the first time a torrent is pulled into TorBox's cache. The
    // StreamWarmer kicks one of these off on details-open; if the user then taps Play before it
    // finishes, the play path used to start a SECOND, fully independent resolve for the very same
    // torrent — two multi-minute round-trips for one stream.
    //
    // The lower-level dedup in DirectDebridPlaybackResolver.inFlightResolves does NOT cover this
    // case: its key (debridResolveCacheKey) is null for shared-backend-TorBox-key users (no
    // personal active resolver), so those resolves skip dedup entirely. This coordinator dedups at
    // the apiKey-independent [resolvedStreamCacheKey] level — the exact key warm and play agree on —
    // so warm and play ALWAYS share one in-flight resolve.
    //
    // Contract: callers pass the resolvedStreamCacheKey and a suspend resolver that returns the
    // direct CDN url (or null on failure/timeout). The first caller for a key runs the resolver;
    // concurrent callers await the same Deferred. A successful url is written to the cache so later
    // plays are instant. Failures are NOT cached (next caller retries).

    private val inFlight = mutableMapOf<String, Deferred<String?>>()

    /**
     * True if a resolve for [key] is currently running (started by a warm or an earlier play).
     * The play path uses this to show a "Preparing stream…" state and to allow waiting past the
     * normal play-path timeout, since a genuinely-progressing cold resolve can legitimately exceed
     * it.
     */
    fun isResolving(key: String?): Boolean {
        val k = key ?: return false
        return synchronized(lock) { inFlight[k]?.isActive == true }
    }

    /**
     * Returns the cached url for [key] if fresh; otherwise resolves it via [resolver], deduping so
     * that a warm already in flight for the same key is AWAITED instead of launching a duplicate.
     *
     * [resolver] must be launched on a long-lived scope by the caller's coroutine — it runs inside
     * the first caller's coroutine here, so [scope] ownership stays with the caller. Returns null
     * if the resolve fails. The CompletableDeferred is registered BEFORE [resolver] runs so a
     * concurrent caller arriving mid-resolve attaches to it.
     */
    suspend fun resolveSingleFlight(key: String?, resolver: suspend () -> String?): String? {
        val k = key ?: return resolver()
        get(k)?.let { return it }

        // Register intent (or attach to an existing in-flight resolve) atomically. The result is
        // either a fresh cached url to return immediately, an existing Deferred to await, or a
        // freshly-owned Deferred this caller must drive.
        val registration: SingleFlightRegistration = synchronized(lock) {
            // Re-check cache under lock — a resolve may have completed between get() and here.
            val fresh = cache[k]?.takeIf { epochMs() - it.resolvedAt in 0..TTL_MS }
            if (fresh != null) {
                SingleFlightRegistration.Cached(fresh.url)
            } else {
                val running = inFlight[k]
                if (running != null && running.isActive) {
                    SingleFlightRegistration.Attach(running)
                } else {
                    val d = CompletableDeferred<String?>()
                    inFlight[k] = d
                    SingleFlightRegistration.Own(d)
                }
            }
        }

        when (registration) {
            is SingleFlightRegistration.Cached -> return registration.url
            is SingleFlightRegistration.Attach -> Unit
            is SingleFlightRegistration.Own -> Unit
        }

        val existing: Deferred<String?>? = (registration as? SingleFlightRegistration.Attach)?.deferred
        val owned: CompletableDeferred<String?>? = (registration as? SingleFlightRegistration.Own)?.deferred

        if (existing != null) {
            log.d { "resolveSingleFlight: awaiting in-flight resolve for key=$k" }
            return try {
                existing.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

        val deferred = owned!!
        return try {
            val url = resolver()
            if (url != null && url.isNotBlank()) {
                put(key = k, url = url)
            }
            deferred.complete(url)
            url
        } catch (e: CancellationException) {
            // Don't strand awaiters on cancellation of the owner: complete with null so they fall
            // back to their own resolve rather than hanging on a dead Deferred.
            deferred.complete(null)
            throw e
        } catch (e: Exception) {
            deferred.complete(null)
            null
        } finally {
            synchronized(lock) {
                if (inFlight[k] === deferred) inFlight.remove(k)
            }
        }
    }

    private sealed interface SingleFlightRegistration {
        data class Cached(val url: String) : SingleFlightRegistration
        data class Attach(val deferred: Deferred<String?>) : SingleFlightRegistration
        data class Own(val deferred: CompletableDeferred<String?>) : SingleFlightRegistration
    }
}

/**
 * Stable cache key for a debrid stream's resolved url. Built from the debrid identity fields
 * (service|infoHash|fileIdx|filename + season/episode) so that warm-time and play-time
 * computations agree regardless of whether the resolve used the user's key or the shared key.
 *
 * Returns null for streams that are not direct-debrid candidates (nothing to warm/cache).
 */
fun StreamItem.resolvedStreamCacheKey(season: Int?, episode: Int?): String? {
    val resolve = clientResolve ?: return null
    if (!isDirectDebridStream) return null
    val providerId = DebridProviders.byId(resolve.service)?.id ?: resolve.service?.lowercase() ?: return null
    val identity = resolve.infoHash
        ?: resolve.magnetUri
        ?: resolve.torrentName
        ?: resolve.filename
        ?: return null
    return listOf(
        providerId,
        identity.trim().lowercase(),
        resolve.fileIdx?.toString().orEmpty(),
        (resolve.filename ?: behaviorHints.filename).orEmpty().trim().lowercase(),
        (season ?: resolve.season)?.toString().orEmpty(),
        (episode ?: resolve.episode)?.toString().orEmpty(),
    ).joinToString("|")
}
