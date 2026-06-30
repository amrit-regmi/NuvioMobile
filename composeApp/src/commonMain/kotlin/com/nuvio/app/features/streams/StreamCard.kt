package com.nuvio.app.features.streams

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.features.debrid.DebridProviders

@Composable
internal fun StreamCard(
    stream: StreamItem,
    enabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    showFileSizeBadges: Boolean,
    showAddonLogo: Boolean,
    badgePlacement: StreamBadgePlacement,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    currentLabel: String? = null,
) {
    val cardShape = RoundedCornerShape(12.dp)
    val badgeImages = stream.badges.filter { it.imageURL.isNotBlank() }
    val hasBadges = badgeImages.isNotEmpty() || (showFileSizeBadges && stream.behaviorHints.videoSize != null)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .shadow(
                elevation = 2.dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.04f),
            )
            .clip(cardShape)
            .background(
                if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.White.copy(alpha = 0.05f)
                },
            )
            .then(
                if (isCurrent) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.52f),
                        shape = cardShape,
                    )
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val info = stream.streamInfo
            if (info != null) {
                // Unified, structured stream row (drops the raw torrent name).
                StreamInfoContent(
                    info = info,
                    isCurrent = isCurrent,
                    currentLabel = currentLabel,
                )
            } else {
                // Legacy rendering for addon streams without backend streamInfo.
                if (hasBadges && badgePlacement == StreamBadgePlacement.TOP) {
                    StreamCardBadgeRow(
                        badgeImages = badgeImages,
                        stream = stream,
                        showFileSizeBadges = showFileSizeBadges,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                StreamNameWithInstantService(
                    stream = stream,
                    appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
                ) {
                    if (isCurrent && !currentLabel.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CurrentStreamBadge(label = currentLabel)
                    }
                }

                val subtitle = stream.streamSubtitle
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (hasBadges && badgePlacement == StreamBadgePlacement.BOTTOM) {
                    Spacer(modifier = Modifier.height(5.dp))
                    StreamCardBadgeRow(
                        badgeImages = badgeImages,
                        stream = stream,
                        showFileSizeBadges = showFileSizeBadges,
                    )
                }
            }
        }

        if (showAddonLogo) {
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!stream.addonLogo.isNullOrBlank()) {
                    AsyncImage(
                        model = stream.addonLogo,
                        contentDescription = stream.addonName,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stream.addonName,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StreamCardBadgeRow(
    badgeImages: List<StreamBadge>,
    stream: StreamItem,
    showFileSizeBadges: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        badgeImages.forEach { badge ->
            StreamBadgeImage(badge = badge)
        }
        if (showFileSizeBadges) {
            StreamFileSizeBadge(stream = stream)
        }
    }
}

@Composable
private fun StreamNameWithInstantService(
    stream: StreamItem,
    appendInstantServiceToDefaultName: Boolean,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val nameStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )
    val instantLabel = if (appendInstantServiceToDefaultName) {
        stream.instantServiceLabel()
    } else {
        null
    }
    val showInstantLabel = instantLabel != null
    val visibleState = remember(stream.streamLabel) {
        MutableTransitionState(showInstantLabel)
    }
    visibleState.targetState = showInstantLabel

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stream.streamLabel,
            modifier = Modifier.weight(1f, fill = false),
            style = nameStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(animationSpec = tween(durationMillis = 260)) +
                expandHorizontally(
                    animationSpec = tween(durationMillis = 260),
                    expandFrom = Alignment.Start,
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 120)) +
                shrinkHorizontally(
                    animationSpec = tween(durationMillis = 120),
                    shrinkTowards = Alignment.Start,
                ),
            label = "streamNameInstantService",
        ) {
            Text(
                text = " ${instantLabel.orEmpty()}",
                style = nameStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        trailingContent()
    }
}

@Composable
private fun StreamInfoContent(
    info: StreamInfo,
    isCurrent: Boolean,
    currentLabel: String?,
) {
    val titleStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )
    val lineStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = "  ·  "

    // Title (+ year) only; cache pill trails on the right.
    val titleLine = buildString {
        append(info.title?.takeIf { it.isNotBlank() } ?: "Stream")
        info.year?.let { append(" (").append(it).append(")") }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = titleLine,
            modifier = Modifier.weight(1f),
            style = titleStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        info.cacheState?.let { state ->
            Spacer(modifier = Modifier.width(8.dp))
            CacheStatusPill(state)
        }
    }

    if (isCurrent && !currentLabel.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        CurrentStreamBadge(label = currentLabel)
    }

    // Quality: [Hd] S05 E16 · 1080p · BluRay
    val seText = buildString {
        info.season?.let { append("S").append(it.toString().padStart(2, '0')) }
        info.episode?.let {
            if (isNotEmpty()) append(" ")
            append("E").append(it.toString().padStart(2, '0'))
        }
    }
    val qualityText = listOfNotNull(
        seText.takeIf { it.isNotBlank() },
        info.resolution?.takeIf { it.isNotBlank() },
        info.source?.takeIf { it.isNotBlank() },
    ).joinToString(dot)
    if (qualityText.isNotBlank()) {
        InfoRow {
            InfoSegment(Icons.Rounded.Hd, qualityText, secondary, lineStyle, Modifier.weight(1f, fill = false))
        }
    }

    // Video + audio on one line: [Movie] x264 · 10-bit · HDR10   [Speaker] DTS-HD MA 5.1
    val videoText = (
        listOfNotNull(
            info.videoCodec?.takeIf { it.isNotBlank() },
            info.bitDepth?.takeIf { it.isNotBlank() },
        ) + info.dynamicRange.filter { it.isNotBlank() }
        ).joinToString(dot)
    val audioText = listOfNotNull(
        info.audioCodec?.takeIf { it.isNotBlank() },
        info.audioChannels?.takeIf { it.isNotBlank() },
    ).joinToString(" ")
    if (videoText.isNotBlank() || audioText.isNotBlank()) {
        InfoRow {
            if (videoText.isNotBlank()) {
                InfoSegment(Icons.Rounded.Movie, videoText, secondary, lineStyle, Modifier.weight(1f, fill = false))
            }
            if (audioText.isNotBlank()) {
                InfoSegment(Icons.AutoMirrored.Rounded.VolumeUp, audioText, secondary, lineStyle, Modifier.weight(1f, fill = false))
            }
        }
    }

    // Size: [Storage] 9 GB · 56m · 18 Mbps
    val sizeText = listOfNotNull(
        info.sizeLabel?.takeIf { it.isNotBlank() },
        info.runtimeLabel?.takeIf { it.isNotBlank() },
        info.bitrateLabel?.takeIf { it.isNotBlank() },
    ).joinToString(dot)
    if (sizeText.isNotBlank()) {
        InfoRow {
            InfoSegment(Icons.Rounded.Storage, sizeText, secondary, lineStyle, Modifier.weight(1f, fill = false))
        }
    }

    // Languages: [Language] EN · ES    [Subtitles] EN · ES
    val audioLangs = info.audioLanguages.toLangTags()
    val subLangs = info.subtitleLanguages.toLangTags()
    if (audioLangs.isNotBlank() || subLangs.isNotBlank()) {
        InfoRow {
            if (audioLangs.isNotBlank()) {
                InfoSegment(Icons.Rounded.Language, audioLangs, secondary, lineStyle, Modifier.weight(1f, fill = false))
            }
            if (subLangs.isNotBlank()) {
                InfoSegment(Icons.Rounded.Subtitles, subLangs, secondary, lineStyle, Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
private fun InfoRow(content: @Composable RowScope.() -> Unit) {
    Spacer(modifier = Modifier.height(3.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun InfoSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: Color,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = style,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CacheStatusPill(state: StreamCacheState) {
    // Readiness chip — the ONLY colour in the row: Instant=gold, Cached=green, rest grey.
    val (icon, tint) = when (state) {
        StreamCacheState.INSTANT -> Icons.Rounded.Bolt to Color(0xFFE0A800)        // gold
        StreamCacheState.CACHED -> Icons.Rounded.CloudDone to Color(0xFF43A047)    // green
        StreamCacheState.DOWNLOADING -> Icons.Rounded.Downloading to Color(0xFF1E88E5) // blue (in progress)
        StreamCacheState.NOT_CACHED -> Icons.Rounded.CloudDownload to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = state.label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CurrentStreamBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun StreamItem.instantServiceLabel(): String? {
    val status = debridCacheStatus ?: return null
    if (status.state != StreamDebridCacheState.CACHED) return null
    val providerLabel = DebridProviders.shortName(status.providerId)
        .ifBlank { status.providerName.trim() }
        .ifBlank { DebridProviders.displayName(status.providerId) }
    return "- $providerLabel Instant"
}
