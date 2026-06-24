package com.nuvio.app.core.content

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.details.MetaDetails

/**
 * Abstraction over WHERE the app's content (home rows, catalogs, meta/details,
 * streams, search, recommendations) comes from.
 *
 * The private-backend fork replaces NuvioMobile's "iterate every installed Stremio
 * addon" model with a single [PrivateBackendContentSource] that talks to OUR FastAPI
 * (taste-engine). The backend itself speaks the Stremio addon protocol at
 * `/catalog-addon/...`, so the existing Stremio fetch/parse pipeline is reused — the
 * content source just decides which addon(s) feed it.
 *
 * Methods that return [ManagedAddon]s let the existing content repos keep operating
 * on the addon shape (catalog/meta/stream resource URLs) while sourcing those addons
 * from our backend instead of [com.nuvio.app.features.addons.AddonRepository].
 */
interface ContentSource {

    /**
     * The addon(s) that should drive content (home/catalog/search). For the private
     * backend this is exactly one synthetic addon pointing at `/catalog-addon`.
     * Fetched + cached lazily; safe to call repeatedly.
     */
    suspend fun contentAddons(): List<ManagedAddon>

    /** The single primary content addon, or null if its manifest cannot be loaded. */
    suspend fun primaryAddon(): ManagedAddon?

    /** Fetch one catalog page (home row / catalog screen) from this source. */
    suspend fun catalogPage(
        type: String,
        catalogId: String,
        genre: String? = null,
        search: String? = null,
        skip: Int? = null,
        maxItems: Int? = null,
    ): CatalogPage

    /** Fetch meta/details for an item. Returns null when the source has no meta. */
    suspend fun meta(type: String, id: String): MetaDetails?

    /** Search across this source for [query] of [type]. */
    suspend fun search(type: String, query: String, skip: Int? = null): CatalogPage
}
