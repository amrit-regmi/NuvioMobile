package com.nuvio.app.features.details.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.AggregatedRatingsRow
import com.nuvio.app.core.ui.unifyAggregatedRatings
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.details.formatRuntimeForDisplay
import com.nuvio.app.features.details.formatMetaReleaseLineForDetails
import com.nuvio.app.features.home.StreamStatus
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DetailMetaInfo(
    meta: MetaDetails,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val releaseLine = formatMetaReleaseLineForDetails(meta)
        val runtimeText = formatRuntimeForDisplay(meta.runtime)
        val ageBadge = meta.ageRating?.trim()?.takeIf { it.isNotBlank() }

        // "No streams" gate. The backend attaches streamStatus to /meta (catalog/main.py); an
        // UNAVAILABLE value means no playable/queueable stream exists. Fail-open — only an explicit
        // UNAVAILABLE shows the pill (UNKNOWN/missing is treated as available). A downloaded
        // (offline-playable) title plays regardless of the backend status, so it NEVER shows
        // "No streams" and instead surfaces a "Downloaded" pill. Reads local downloads state only
        // (no network) so it stays correct fully offline.
        val downloadedContentIds by DownloadsRepository.downloadedContentIds.collectAsState()
        val isDownloaded = downloadedContentIds.contains(DownloadsRepository.baseContentId(meta.id))
        val backendStreamUnavailable = meta.streamStatus == StreamStatus.UNAVAILABLE
        val isStreamUnavailable = backendStreamUnavailable && !isDownloaded
        val showDownloadedPill = backendStreamUnavailable && isDownloaded

        // Unify ALL ratings into one deduped set, mirroring NuvioTV's HeroSection. Fold the
        // inline meta.imdbRating into the aggregated (/catalog-addon/ratings → externalRatings)
        // set, but never duplicate imdb: prefer the aggregated imdb value when present, else
        // fall back to the meta value. Placement rule: a SINGLE rating stays inline on the
        // genre/year row; TWO OR MORE ratings get their own row directly below it.
        val unifiedRatings = remember(meta.externalRatings, meta.imdbRating) {
            unifyAggregatedRatings(meta.externalRatings, meta.imdbRating)
        }
        val showRatingsInline = unifiedRatings.size == 1
        val showRatingsOwnRow = unifiedRatings.size >= 2

        val hasMetaRow = releaseLine != null ||
            runtimeText != null ||
            ageBadge != null ||
            showRatingsInline ||
            isStreamUnavailable ||
            showDownloadedPill
        if (hasMetaRow) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                releaseLine?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                    )
                }
                runtimeText?.let { rt ->
                    Text(
                        text = rt,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                    )
                }
                ageBadge?.let { badge ->
                    DetailHeroMetaBadge(text = badge)
                }
                // No playable/queueable stream for this title → surface a muted error pill beside
                // the release/age row (mirrors the home hero's "No streams" pill).
                if (isStreamUnavailable) {
                    DetailNoStreamsPill()
                }
                // Downloaded / available offline → accent pill in place of the "No streams" pill.
                if (showDownloadedPill) {
                    DetailDownloadedPill()
                }
                // Single rating → inline on the genre/year row.
                if (showRatingsInline) {
                    AggregatedRatingsRow(ratings = unifiedRatings, ownRow = false)
                }
            }
        }

        // Two or more ratings → their OWN row, directly below the genre/year row.
        AnimatedVisibility(
            visible = showRatingsOwnRow,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            AggregatedRatingsRow(
                ratings = unifiedRatings,
            )
        }

        if (meta.director.isNotEmpty()) {
            MetaLabelValueRow(
                label = stringResource(Res.string.details_director),
                value = meta.director.joinToString(", "),
            )
        }

        if (meta.writer.isNotEmpty()) {
            MetaLabelValueRow(
                label = stringResource(Res.string.details_writer),
                value = meta.writer.joinToString(", "),
            )
        }

        if (!meta.description.isNullOrBlank()) {
            var expanded by remember { mutableStateOf(false) }
            var canExpand by remember(meta.description) { mutableStateOf(false) }
            Column(
                modifier = Modifier.animateContentSize(),
            ) {
                Text(
                    text = meta.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 22.sp,
                    onTextLayout = { result ->
                        if (!expanded) {
                            canExpand = result.hasVisualOverflow
                        }
                    },
                )
                if (canExpand) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (expanded) {
                            stringResource(Res.string.details_show_less)
                        } else {
                            stringResource(Res.string.details_show_more)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaLabelValueRow(
    label: String,
    value: String,
) {
    Row {
        Text(
            text = "$label:  ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DetailHeroMetaBadge(
    text: String,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = Modifier
            .border(
                border = BorderStroke(1.dp, contentColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailDownloadedPill() {
    // Same pill shape/size/typography as DetailNoStreamsPill, tinted with the theme primary
    // (accent) color to signal the title is available offline.
    val accentColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .background(
                color = accentColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp),
            )
            .border(
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.meta_downloaded_pill),
            style = MaterialTheme.typography.labelMedium,
            color = accentColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailNoStreamsPill() {
    // Same pill shape/size/typography as DetailHeroMetaBadge, tinted with the theme error color
    // (muted background + solid border/text) rather than a hardcoded palette.
    val errorColor = MaterialTheme.colorScheme.error
    Box(
        modifier = Modifier
            .background(
                color = errorColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp),
            )
            .border(
                border = BorderStroke(1.dp, errorColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.meta_no_streams_pill),
            style = MaterialTheme.typography.labelMedium,
            color = errorColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
