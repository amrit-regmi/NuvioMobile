package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.network.PrivateBackend
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_builtin_advanced_section
import nuvio.composeapp.generated.resources.settings_builtin_backend_logout_confirm
import nuvio.composeapp.generated.resources.settings_builtin_backend_logout_message
import nuvio.composeapp.generated.resources.settings_builtin_backend_logout_title
import nuvio.composeapp.generated.resources.settings_builtin_backend_url_dialog_description
import nuvio.composeapp.generated.resources.settings_builtin_backend_url_invalid
import nuvio.composeapp.generated.resources.settings_builtin_backend_url_pending
import nuvio.composeapp.generated.resources.settings_builtin_backend_url_reset
import nuvio.composeapp.generated.resources.settings_builtin_backend_url_title
import nuvio.composeapp.generated.resources.settings_builtin_catalog_description
import nuvio.composeapp.generated.resources.settings_builtin_catalog_title
import nuvio.composeapp.generated.resources.settings_builtin_providers_section_providers
import nuvio.composeapp.generated.resources.settings_builtin_recommendations_description
import nuvio.composeapp.generated.resources.settings_builtin_recommendations_title
import nuvio.composeapp.generated.resources.settings_builtin_stream_description
import nuvio.composeapp.generated.resources.settings_builtin_stream_title
import nuvio.composeapp.generated.resources.settings_builtin_subtitle_description
import nuvio.composeapp.generated.resources.settings_builtin_subtitle_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.builtInProvidersSettingsContent(
    isTablet: Boolean,
    onDebridClick: () -> Unit = {},
) {
    // ── Providers (the 3+1 toggles) ────────────────────────────────────────────
    item {
        val homeSettings by remember {
            HomeCatalogSettingsRepository.snapshot()
            HomeCatalogSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val builtInProviders by remember {
            BuiltInProvidersSettingsRepository.ensureLoaded()
            BuiltInProvidersSettingsRepository.uiState
        }.collectAsStateWithLifecycle()

        SettingsSection(
            title = stringResource(Res.string.settings_builtin_providers_section_providers),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_builtin_catalog_title),
                    description = stringResource(Res.string.settings_builtin_catalog_description),
                    checked = homeSettings.useBuiltinCatalog,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setUseBuiltinCatalog,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_builtin_recommendations_title),
                    description = stringResource(Res.string.settings_builtin_recommendations_description),
                    checked = homeSettings.useRecommendations,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setUseRecommendations,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_builtin_stream_title),
                    description = stringResource(Res.string.settings_builtin_stream_description),
                    checked = builtInProviders.streamProviderEnabled,
                    isTablet = isTablet,
                    onCheckedChange = BuiltInProvidersSettingsRepository::setStreamProviderEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_builtin_subtitle_title),
                    description = stringResource(Res.string.settings_builtin_subtitle_description),
                    checked = builtInProviders.subtitleProviderEnabled,
                    isTablet = isTablet,
                    onCheckedChange = BuiltInProvidersSettingsRepository::setSubtitleProviderEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Debrid / Download Provider",
                    icon = Icons.Rounded.CloudDownload,
                    isTablet = isTablet,
                    onClick = onDebridClick,
                )
            }
        }
    }

    // ── Advanced (backend URL) ─────────────────────────────────────────────────
    item {
        var showBackendDialog by rememberSaveable { mutableStateOf(false) }
        // showLogoutPrompt = persistent inline banner; showLogoutDialog = the one-shot modal.
        var showLogoutPrompt by rememberSaveable { mutableStateOf(false) }
        var showLogoutDialog by rememberSaveable { mutableStateOf(false) }
        // The pending/effective backend URL. PrivateBackend.baseUrl only reflects the
        // override after the next launch, so track the just-saved value locally to display.
        var currentUrl by rememberSaveable { mutableStateOf(PrivateBackend.baseUrl) }

        SettingsSection(
            title = stringResource(Res.string.settings_builtin_advanced_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_builtin_backend_url_title),
                    description = currentUrl,
                    icon = Icons.Rounded.Dns,
                    isTablet = isTablet,
                    onClick = { showBackendDialog = true },
                )
                if (showLogoutPrompt) {
                    SettingsGroupDivider(isTablet = isTablet)
                    Text(
                        text = stringResource(Res.string.settings_builtin_backend_url_pending),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(
                            horizontal = if (isTablet) 20.dp else 16.dp,
                            vertical = if (isTablet) 16.dp else 14.dp,
                        ),
                    )
                }
            }
        }

        if (showBackendDialog) {
            BackendUrlDialog(
                currentUrl = currentUrl,
                onSave = { newUrl ->
                    // Persist for the next launch; do NOT hot-swap (mirrors NuvioTV).
                    PrivateBackend.setOverride(newUrl)
                    // Reflect the saved value immediately (override, or baked default on reset).
                    currentUrl = newUrl ?: PrivateBackend.defaultBaseUrl
                    showBackendDialog = false
                    showLogoutPrompt = true
                    showLogoutDialog = true
                },
                onDismiss = { showBackendDialog = false },
            )
        }

        if (showLogoutDialog) {
            BackendChangedLogoutDialog(
                // Dismissing keeps the pending banner; the user can log out later.
                onConfirm = { showLogoutDialog = false; showLogoutPrompt = false },
                onDismiss = { showLogoutDialog = false },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BackendUrlDialog(
    currentUrl: String,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable(currentUrl) { mutableStateOf(currentUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidMessage = stringResource(Res.string.settings_builtin_backend_url_invalid)

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_builtin_backend_url_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.settings_builtin_backend_url_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = error != null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        disabledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        // Blank → revert to baked default.
                        onSave(null)
                    }) {
                        Text(stringResource(Res.string.settings_builtin_backend_url_reset))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    Button(onClick = {
                        val normalized = normalizeBackendUrlOrNull(draft)
                        if (normalized == null) {
                            error = invalidMessage
                        } else {
                            onSave(normalized)
                        }
                    }) {
                        Text(stringResource(Res.string.action_save))
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BackendChangedLogoutDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_builtin_backend_logout_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.settings_builtin_backend_logout_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    Button(onClick = {
                        scope.launch { AuthRepository.signOut() }
                        onConfirm()
                    }) {
                        Text(stringResource(Res.string.settings_builtin_backend_logout_confirm))
                    }
                }
            }
        }
    }
}

/**
 * Validates a backend URL: trims, drops a trailing slash, requires https:// and a host
 * with a dot. Returns null when blank/invalid. Mirrors NuvioTV's `normalizeOrNull`.
 */
internal fun normalizeBackendUrlOrNull(url: String): String? {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.isBlank()) return null
    if (!trimmed.startsWith("https://", ignoreCase = true)) return null
    val host = trimmed.substringAfter("://").substringBefore("/").substringBefore(":")
    if (host.isBlank() || !host.contains('.') || host.contains(' ')) return null
    return trimmed
}
