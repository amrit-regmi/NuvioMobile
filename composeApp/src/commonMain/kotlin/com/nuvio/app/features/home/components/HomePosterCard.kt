package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.StreamStatus

@Composable
fun HomePosterCard(
    item: MetaPreview,
    modifier: Modifier = Modifier,
    useLandscapeBackdropMode: Boolean = false,
    isWatched: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val isLandscapeMode = useLandscapeBackdropMode || posterCardStyle.catalogLandscapeModeEnabled

    // Offline-available (downloaded) titles play regardless of the backend stream status, so
    // suppress the "No streams" chip for them. Reads local downloads state only (no network).
    val downloadedContentIds by DownloadsRepository.downloadedContentIds.collectAsState()
    val isDownloaded = downloadedContentIds.contains(DownloadsRepository.baseContentId(item.id))

    NuvioPosterCard(
        title = item.name,
        imageUrl = if (isLandscapeMode) (item.banner ?: item.poster) else item.poster,
        modifier = modifier,
        shape = if (isLandscapeMode) NuvioPosterShape.Landscape else item.posterShape.toNuvioPosterShape(),
        detailLine = if (isLandscapeMode || posterCardStyle.hideLabelsEnabled) null else item.releaseInfo?.let { formatReleaseDateForDisplay(it) },
        showTitleBelow = !posterCardStyle.hideLabelsEnabled,
        bottomLeftLogoUrl = if (isLandscapeMode) item.logo else null,
        bottomLeftText = if (isLandscapeMode && item.logo.isNullOrBlank() && !posterCardStyle.hideLabelsEnabled) item.name else null,
        isWatched = isWatched,
        streamUnavailable = item.streamStatus == StreamStatus.UNAVAILABLE,
        isDownloaded = isDownloaded,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

private fun PosterShape.toNuvioPosterShape(): NuvioPosterShape =
    when (this) {
        PosterShape.Poster -> NuvioPosterShape.Poster
        PosterShape.Square -> NuvioPosterShape.Square
        PosterShape.Landscape -> NuvioPosterShape.Landscape
    }
