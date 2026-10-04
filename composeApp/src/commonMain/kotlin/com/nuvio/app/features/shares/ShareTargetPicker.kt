package com.nuvio.app.features.shares

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.shares_picker_empty
import nuvio.composeapp.generated.resources.shares_picker_failed
import nuvio.composeapp.generated.resources.shares_picker_sent
import nuvio.composeapp.generated.resources.shares_picker_title
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * "Recommend to..." target picker — opened from the poster long-press action sheet
 * ([com.nuvio.app.core.ui.NuvioPosterActionSheet]'s new `onRecommend` row, wired in App.kt).
 *
 * Fetches `GET /shares/permissions/granted` ([SharesApi.fetchGrantedSources]) on open — NEVER
 * the full roster (that's the Settings "add someone to receive from" picker, a different flow;
 * see [RecommendPermissionsSettingsPage]) — and sends via `POST /shares` on tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetPicker(
    item: MetaPreview,
    addonBaseUrl: String?,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var targets by remember { mutableStateOf<List<GrantedSourceDto>>(emptyList()) }
    var sendingUserId by remember { mutableStateOf<String?>(null) }

    val failedText = stringResource(Res.string.shares_picker_failed)

    LaunchedEffect(Unit) {
        isLoading = true
        targets = SharesApi.fetchGrantedSources()
        isLoading = false
    }

    fun close() {
        coroutineScope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) }
    }

    NuvioModalBottomSheet(
        onDismissRequest = { close() },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(Res.string.shares_picker_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 14.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            NuvioBottomSheetDivider()

            when {
                isLoading -> Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(horizontal = tokens.spacing.screenHorizontal))
                }
                targets.isEmpty() -> Text(
                    text = stringResource(Res.string.shares_picker_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.screenHorizontal, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(targets, key = { it.userId }) { target ->
                        NuvioBottomSheetActionRow(
                            title = target.name?.takeIf { it.isNotBlank() } ?: target.userId,
                            onClick = {
                                if (sendingUserId != null) return@NuvioBottomSheetActionRow
                                sendingUserId = target.userId
                                coroutineScope.launch {
                                    val success = SharesApi.sendShare(
                                        recipientUserId = target.userId,
                                        item = item,
                                        addonBaseUrl = addonBaseUrl,
                                    )
                                    sendingUserId = null
                                    NuvioToastController.show(
                                        if (success) {
                                            getString(Res.string.shares_picker_sent, target.name ?: target.userId)
                                        } else {
                                            failedText
                                        },
                                    )
                                    if (success) close()
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
