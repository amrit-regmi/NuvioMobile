package com.nuvio.app.core.content

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.PrivateBackend
import com.nuvio.app.features.addons.AddonManifestParser
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ContentSource] backed by OUR private FastAPI (taste-engine), which exposes a
 * Stremio-protocol addon at [PrivateBackend.catalogAddonManifestUrl]
 * (`/catalog-addon/...`). Recommendations, trending/popular/new-releases home rows,
 * meta, streams and search are all served as Stremio resources by that one addon.
 *
 * The Supabase session Bearer is attached automatically by the platform HTTP layer
 * for our backend host (see `BackendAuth` + the addon platform actuals), so the
 * existing Stremio fetch helpers ([fetchCatalogPage], [httpGetText]) "just work"
 * against the authenticated backend.
 */
object PrivateBackendContentSource : ContentSource {
    private val log = Logger.withTag("PrivateBackendContentSource")
    private val manifestMutex = Mutex()

    @Volatile
    private var cachedAddon: ManagedAddon? = null

    @Volatile
    private var cachedForBaseUrl: String? = null

    override suspend fun contentAddons(): List<ManagedAddon> =
        listOfNotNull(primaryAddon())

    override suspend fun primaryAddon(): ManagedAddon? {
        val manifestUrl = PrivateBackend.catalogAddonManifestUrl
        cachedAddon
            ?.takeIf { cachedForBaseUrl == PrivateBackend.baseUrl }
            ?.let { return it }

        return manifestMutex.withLock {
            cachedAddon
                ?.takeIf { cachedForBaseUrl == PrivateBackend.baseUrl }
                ?.let { return@withLock it }

            val loaded = runCatching {
                val payload = httpGetText(manifestUrl)
                val manifest = AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                )
                ManagedAddon(
                    manifestUrl = manifestUrl,
                    manifest = manifest,
                    enabled = true,
                )
            }.onFailure { error ->
                log.e(error) { "Failed to load private backend catalog-addon manifest from $manifestUrl" }
            }.getOrNull()

            if (loaded != null) {
                cachedAddon = loaded
                cachedForBaseUrl = PrivateBackend.baseUrl
            }
            loaded
        }
    }

    /** Drop the cached manifest (e.g. after the backend URL override changes). */
    fun invalidate() {
        cachedAddon = null
        cachedForBaseUrl = null
    }

    override suspend fun catalogPage(
        type: String,
        catalogId: String,
        genre: String?,
        search: String?,
        skip: Int?,
        maxItems: Int?,
    ): CatalogPage =
        fetchCatalogPage(
            manifestUrl = PrivateBackend.catalogAddonManifestUrl,
            type = type,
            catalogId = catalogId,
            genre = genre,
            search = search,
            skip = skip,
            maxItems = maxItems,
        )

    override suspend fun meta(type: String, id: String): MetaDetails? {
        val url = com.nuvio.app.features.addons.buildAddonResourceUrl(
            manifestUrl = PrivateBackend.catalogAddonManifestUrl,
            resource = "meta",
            type = type,
            id = id,
        )
        return runCatching {
            MetaDetailsParser.parse(httpGetText(url))
        }.onFailure { error ->
            log.w(error) { "Backend meta fetch failed for $type/$id" }
        }.getOrNull()
    }

    override suspend fun search(type: String, query: String, skip: Int?): CatalogPage =
        catalogPage(
            type = type,
            catalogId = "search",
            search = query,
            skip = skip,
        )
}

/**
 * App-wide accessor for the active [ContentSource].
 *
 * The private-backend fork uses a single source ([PrivateBackendContentSource]). The
 * content repos that used to iterate `AddonRepository.enabledAddons()` now pull their
 * addon list from here, so all home/catalog/meta/stream/search content resolves
 * through our FastAPI backend instead of arbitrary Stremio addons.
 */
object ContentSourceProvider {
    val current: ContentSource
        get() = PrivateBackendContentSource

    private val _contentAddons =
        kotlinx.coroutines.flow.MutableStateFlow<List<com.nuvio.app.features.addons.ManagedAddon>>(emptyList())

    /**
     * Observable content addons (exactly our backend's catalog-addon once loaded).
     * Compose screens collect this in place of `AddonRepository.uiState`.
     */
    val contentAddonsFlow: kotlinx.coroutines.flow.StateFlow<List<com.nuvio.app.features.addons.ManagedAddon>> =
        _contentAddons

    /**
     * Last-loaded content addons, for synchronous (non-suspend) call sites such as
     * the stream/meta repos. Empty until the backend manifest is first fetched.
     */
    val cachedContentAddons: List<com.nuvio.app.features.addons.ManagedAddon>
        get() = _contentAddons.value

    /** Suspend fetch of the content addons; also refreshes the cache + flow. */
    suspend fun contentAddons(): List<com.nuvio.app.features.addons.ManagedAddon> =
        current.contentAddons().also { _contentAddons.value = it }

    /** Eagerly load the backend manifest so the cache + flow are populated. */
    suspend fun prime() {
        contentAddons()
    }
}
