package com.nuvio.app.features.shares

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.shares_added_to_watchlist
import nuvio.composeapp.generated.resources.shares_allow
import nuvio.composeapp.generated.resources.shares_deny
import nuvio.composeapp.generated.resources.shares_dismiss
import nuvio.composeapp.generated.resources.shares_inbox_empty
import nuvio.composeapp.generated.resources.shares_inbox_title
import nuvio.composeapp.generated.resources.shares_permission_request_message
import nuvio.composeapp.generated.resources.shares_sender_recommends
import org.jetbrains.compose.resources.stringResource

/**
 * Home top-bar "Recommend to..." inbox entry (net new — no existing precedent for a home
 * top bar in this codebase; see HomeScreen.kt). Self-contained: owns its own sheet-open state
 * and reads [ShareInboxRepository.uiState] directly, so it doesn't need threading through
 * HomeScreen's already-large parameter list.
 */
@Composable
fun ShareInboxBell(modifier: Modifier = Modifier) {
    val uiState by ShareInboxRepository.uiState.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { sheetOpen = true }) {
            Icon(
                imageVector = Icons.Rounded.Notifications,
                contentDescription = stringResource(Res.string.shares_inbox_title),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (uiState.badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (uiState.badgeCount > 9) "9+" else uiState.badgeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError,
                )
            }
        }
    }

    if (sheetOpen) {
        ShareInboxSheet(
            items = uiState.items,
            onDismiss = { sheetOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareInboxSheet(
    items: List<InboxItem>,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val addedToWatchlistText = stringResource(Res.string.shares_added_to_watchlist)

    LaunchedEffect(Unit) {
        ShareInboxRepository.refreshNow()
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) }
        },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(Res.string.shares_inbox_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 14.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            NuvioBottomSheetDivider()

            if (items.isEmpty()) {
                Text(
                    text = stringResource(Res.string.shares_inbox_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                    items(items, key = { it.id }) { item ->
                        when (item) {
                            is InboxItem.Share -> ShareInboxRow(
                                item = item,
                                onAddToWatchlist = {
                                    LibraryRepository.save(item.toLibraryItem())
                                    ShareInboxRepository.respondToShare(item.id, added = true)
                                    NuvioToastController.show(addedToWatchlistText)
                                },
                                onDismiss = { ShareInboxRepository.respondToShare(item.id, added = false) },
                            )
                            is InboxItem.PermissionRequest -> PermissionRequestRow(
                                item = item,
                                onAllow = { ShareInboxRepository.respondToPermissionRequest(item.id, allow = true) },
                                onDeny = { ShareInboxRepository.respondToPermissionRequest(item.id, allow = false) },
                            )
                        }
                        NuvioBottomSheetDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareInboxRow(
    item: InboxItem.Share,
    onAddToWatchlist: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 68.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(tokens.colors.surfaceCard),
            ) {
                item.poster?.let { poster ->
                    AsyncImage(
                        model = poster,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxWidth().height(68.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.shares_sender_recommends, item.senderName),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onAddToWatchlist,
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.colors.accent,
                    contentColor = tokens.colors.onAccent,
                ),
            ) {
                Text(stringResource(Res.string.shares_add_to_watchlist))
            }
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(Res.string.shares_dismiss))
            }
        }
    }
}

@Composable
private fun PermissionRequestRow(
    item: InboxItem.PermissionRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(Res.string.shares_permission_request_message, item.requesterName),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
        )
        Spacer()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onAllow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.colors.accent,
                    contentColor = tokens.colors.onAccent,
                ),
            ) {
                Text(stringResource(Res.string.shares_allow))
            }
            OutlinedButton(onClick = onDeny) {
                Text(stringResource(Res.string.shares_deny))
            }
        }
    }
}

@Composable
private fun Spacer() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(10.dp))
}

private fun InboxItem.Share.toLibraryItem(): LibraryItem = LibraryItem(
    id = contentId,
    type = contentType,
    name = name,
    poster = poster,
    banner = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    posterShape = PosterShape.Poster,
    addonBaseUrl = addonBaseUrl,
    imdbId = contentId.takeIf { it.startsWith("tt") },
    savedAtEpochMs = 0L,
)
