package com.nuvio.app.features.home

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.catalog.CatalogTarget

data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val posterShape: PosterShape = PosterShape.Poster,
    val description: String? = null,
    val releaseInfo: String? = null,
    val rawReleaseDate: String? = null,
    val popularity: Double? = null,
    val voteCount: Int? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val streamStatus: StreamStatus = StreamStatus.UNKNOWN,
)

fun MetaPreview.stableKey(): String = "$type:$id"

/**
 * Backend-computed stream availability for a catalog/browse item, recomputed on every fetch
 * (no client caching). Mirrors NuvioTV's StreamStatus. Only [UNAVAILABLE] items are grayed out
 * in browse/catalog rows; [INSTANT]/[QUEUEABLE] have a playable/queueable link and render normally.
 * Missing/unknown values map to [UNKNOWN] and are treated as available (never grayed).
 */
enum class StreamStatus {
    UNKNOWN,     // not yet checked / not provided
    INSTANT,     // cached in Torbox, ready to play
    QUEUEABLE,   // known torrent, can be added to Torbox
    UNAVAILABLE; // no known hashes

    companion object {
        fun fromString(s: String?): StreamStatus = when (s?.lowercase()) {
            "instant" -> INSTANT
            "queueable" -> QUEUEABLE
            "unavailable" -> UNAVAILABLE
            else -> UNKNOWN
        }
    }
}

enum class PosterShape {
    Poster,
    Square,
    Landscape,
}

data class HomeCatalogSection(
    val key: String,
    val title: String,
    val subtitle: String,
    val addonName: String,
    val target: CatalogTarget,
    val items: List<MetaPreview>,
    val availableItemCount: Int = items.size,
    val hasMore: Boolean = false,
)

fun HomeCatalogSection.canOpenCatalog(previewLimit: Int): Boolean =
    availableItemCount > previewLimit || hasMore

data class HomeUiState(
    val isLoading: Boolean = false,
    val heroItems: List<MetaPreview> = emptyList(),
    val sections: List<HomeCatalogSection> = emptyList(),
    val errorMessage: String? = null,
)

internal data class CatalogRequest(
    val addon: ManagedAddon,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val supportsPagination: Boolean,
)
