package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.shares.MyPermissionRequestDto
import com.nuvio.app.features.shares.RosterUserDto
import com.nuvio.app.features.shares.SharesApi
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_shares_add_action
import nuvio.composeapp.generated.resources.settings_shares_alias_hint
import nuvio.composeapp.generated.resources.settings_shares_empty_allowed
import nuvio.composeapp.generated.resources.settings_shares_empty_pending
import nuvio.composeapp.generated.resources.settings_shares_remove_action
import nuvio.composeapp.generated.resources.settings_shares_request_failed
import nuvio.composeapp.generated.resources.settings_shares_request_sent
import nuvio.composeapp.generated.resources.settings_shares_roster_empty
import nuvio.composeapp.generated.resources.settings_shares_roster_search_placeholder
import nuvio.composeapp.generated.resources.settings_shares_roster_title
import nuvio.composeapp.generated.resources.settings_shares_section_allowed
import nuvio.composeapp.generated.resources.settings_shares_section_pending
import nuvio.composeapp.generated.resources.settings_shares_status_pending
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → "Receive recommendations from" (parallel-swinging-flurry plan, Feature 2).
 * Self-contained like [addonsSettingsContent] / [accountSettingsContent] — owns its own network
 * calls via [SharesApi] rather than threading state through SettingsScreen's already-large
 * parameter lists.
 */
internal fun LazyListScope.recommendPermissionsSettingsContent(isTablet: Boolean) {
    item {
        RecommendPermissionsSettingsBody(isTablet = isTablet)
    }
}

@Composable
private fun RecommendPermissionsSettingsBody(isTablet: Boolean) {
    var isLoading by remember { mutableStateOf(true) }
    var mine by remember { mutableStateOf<List<MyPermissionRequestDto>>(emptyList()) }
    var showRosterPicker by remember { mutableStateOf(false) }
    var editingAliasId by remember { mutableStateOf<String?>(null) }
    var aliasDraft by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    suspend fun refresh() {
        isLoading = true
        mine = SharesApi.fetchMyPermissionRequests()
        isLoading = false
    }

    LaunchedEffect(Unit) { refresh() }

    val allowed = mine.filter { it.status == "allowed" }
    val pending = mine.filter { it.status == "pending" }

    SettingsSection(
        title = stringResource(Res.string.settings_shares_section_allowed),
        isTablet = isTablet,
        actions = {
            com.nuvio.app.core.ui.NuvioIconActionButton(
                icon = Icons.Rounded.Add,
                contentDescription = stringResource(Res.string.settings_shares_add_action),
                onClick = { showRosterPicker = true },
            )
        },
    ) {
        SettingsGroup(isTablet = isTablet) {
            when {
                isLoading -> CircularProgressIndicator(
                    modifier = Modifier.padding(20.dp),
                )
                allowed.isEmpty() -> Text(
                    text = stringResource(Res.string.settings_shares_empty_allowed),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
                else -> allowed.forEachIndexed { index, row ->
                    AllowedSourceRow(
                        row = row,
                        isEditing = editingAliasId == row.id,
                        aliasDraft = aliasDraft,
                        onAliasDraftChange = { aliasDraft = it },
                        onStartEdit = {
                            editingAliasId = row.id
                            aliasDraft = row.alias.orEmpty()
                        },
                        onSaveAlias = {
                            val id = row.id
                            val alias = aliasDraft
                            editingAliasId = null
                            coroutineScope.launch {
                                if (SharesApi.updateAlias(id, alias)) {
                                    mine = mine.map { if (it.id == id) it.copy(alias = alias) else it }
                                }
                            }
                        },
                        onCancelEdit = { editingAliasId = null },
                        onRemove = {
                            val id = row.id
                            coroutineScope.launch {
                                if (SharesApi.removePermission(id)) {
                                    mine = mine.filterNot { it.id == id }
                                }
                            }
                        },
                        isTablet = isTablet,
                    )
                    if (index != allowed.lastIndex) SettingsGroupDivider(isTablet = isTablet)
                }
            }
        }
    }

    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))

    SettingsSection(
        title = stringResource(Res.string.settings_shares_section_pending),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            if (pending.isEmpty()) {
                Text(
                    text = stringResource(Res.string.settings_shares_empty_pending),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
            } else {
                pending.forEachIndexed { index, row ->
                    PendingRequestRow(
                        row = row,
                        isTablet = isTablet,
                        onRemove = {
                            val id = row.id
                            coroutineScope.launch {
                                if (SharesApi.removePermission(id)) {
                                    mine = mine.filterNot { it.id == id }
                                }
                            }
                        },
                    )
                    if (index != pending.lastIndex) SettingsGroupDivider(isTablet = isTablet)
                }
            }
        }
    }

    if (showRosterPicker) {
        RosterPickerSheet(
            onDismiss = { showRosterPicker = false },
            onRequested = {
                coroutineScope.launch { refresh() }
            },
        )
    }
}

@Composable
private fun AllowedSourceRow(
    row: MyPermissionRequestDto,
    isEditing: Boolean,
    aliasDraft: String,
    onAliasDraftChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSaveAlias: () -> Unit,
    onCancelEdit: () -> Unit,
    onRemove: () -> Unit,
    isTablet: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.sourceName?.takeIf { it.isNotBlank() } ?: row.sourceId,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isEditing) {
                OutlinedTextField(
                    value = aliasDraft,
                    onValueChange = onAliasDraftChange,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    singleLine = true,
                    label = { Text(stringResource(Res.string.settings_shares_alias_hint)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = tokens.colors.borderFocus,
                        unfocusedBorderColor = tokens.colors.borderDefault,
                    ),
                )
            } else if (!row.alias.isNullOrBlank()) {
                Text(
                    text = row.alias,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        if (isEditing) {
            IconButton(onClick = onSaveAlias) {
                Icon(imageVector = Icons.Rounded.Check, contentDescription = null, tint = tokens.colors.accent)
            }
            IconButton(onClick = onCancelEdit) {
                Icon(imageVector = Icons.Rounded.Close, contentDescription = null, tint = tokens.colors.textMuted)
            }
        } else {
            IconButton(onClick = onStartEdit) {
                Icon(imageVector = Icons.Rounded.Edit, contentDescription = null, tint = tokens.colors.textMuted)
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(Res.string.settings_shares_remove_action),
                    tint = tokens.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun PendingRequestRow(row: MyPermissionRequestDto, isTablet: Boolean, onRemove: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.sourceName?.takeIf { it.isNotBlank() } ?: row.sourceId,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(Res.string.settings_shares_status_pending),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(Res.string.settings_shares_remove_action),
                tint = tokens.colors.textMuted,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RosterPickerSheet(
    onDismiss: () -> Unit,
    onRequested: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var roster by remember { mutableStateOf<List<RosterUserDto>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var requestedIds by remember { mutableStateOf(setOf<String>()) }

    val sentText = stringResource(Res.string.settings_shares_request_sent)
    val failedText = stringResource(Res.string.settings_shares_request_failed)

    LaunchedEffect(Unit) {
        isLoading = true
        roster = SharesApi.fetchRoster()
        isLoading = false
    }

    val filtered = remember(roster, query) {
        if (query.isBlank()) {
            roster
        } else {
            roster.filter { (it.name ?: it.userId).contains(query, ignoreCase = true) }
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) }
        },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(Res.string.settings_shares_roster_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 14.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.settings_shares_roster_search_placeholder)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tokens.colors.borderFocus,
                    unfocusedBorderColor = tokens.colors.borderDefault,
                ),
            )
            NuvioBottomSheetDivider()

            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.padding(32.dp))
                filtered.isEmpty() -> Text(
                    text = stringResource(Res.string.settings_shares_roster_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(filtered, key = { it.userId }) { user ->
                        NuvioBottomSheetActionRow(
                            title = user.name?.takeIf { it.isNotBlank() } ?: user.userId,
                            onClick = {
                                if (user.userId in requestedIds) return@NuvioBottomSheetActionRow
                                requestedIds = requestedIds + user.userId
                                coroutineScope.launch {
                                    val success = SharesApi.requestPermission(user.userId)
                                    NuvioToastController.show(if (success) sentText else failedText)
                                    if (success) onRequested()
                                }
                            },
                        )
                        NuvioBottomSheetDivider()
                    }
                }
            }
        }
    }
}
