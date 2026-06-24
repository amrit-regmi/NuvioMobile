package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BuiltInProvidersUiState(
    /** Stream provider toggle. Maps to `nuvio_profile_settings.streamProvider` != "addons". */
    val streamProviderEnabled: Boolean = true,
    /** Subtitle provider toggle. Gates OUR backend subtitles in the player picker. */
    val subtitleProviderEnabled: Boolean = true,
)

/**
 * Holds the two profile-level built-in provider toggles (stream + subtitle), which the TV
 * app and mobile app SHARE via `nuvio_profile_settings`. Local persistence is offline-first
 * via [BuiltInProvidersStorage]; cross-platform sync runs through
 * [BuiltInProvidersSettingsSyncService] (Supabase RPCs on the shared profile-settings row).
 */
object BuiltInProvidersSettingsRepository {
    private val _uiState = MutableStateFlow(BuiltInProvidersUiState())
    val uiState: StateFlow<BuiltInProvidersUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var streamProviderEnabled = true
    private var subtitleProviderEnabled = true

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        streamProviderEnabled = BuiltInProvidersStorage.loadStreamProviderEnabled() ?: true
        subtitleProviderEnabled = BuiltInProvidersStorage.loadSubtitleProviderEnabled() ?: true
        publish()
    }

    fun setStreamProviderEnabled(enabled: Boolean) {
        ensureLoaded()
        if (streamProviderEnabled == enabled) return
        streamProviderEnabled = enabled
        publish()
        BuiltInProvidersStorage.saveStreamProviderEnabled(enabled)
        BuiltInProvidersSettingsSyncService.triggerPush()
    }

    fun setSubtitleProviderEnabled(enabled: Boolean) {
        ensureLoaded()
        if (subtitleProviderEnabled == enabled) return
        subtitleProviderEnabled = enabled
        publish()
        BuiltInProvidersStorage.saveSubtitleProviderEnabled(enabled)
        BuiltInProvidersSettingsSyncService.triggerPush()
    }

    /** Whether OUR backend's stream resolution is enabled (synchronous read for repos). */
    fun isStreamProviderEnabled(): Boolean {
        ensureLoaded()
        return streamProviderEnabled
    }

    /** Whether OUR backend's subtitles are enabled (synchronous read for the player). */
    fun isSubtitleProviderEnabled(): Boolean {
        ensureLoaded()
        return subtitleProviderEnabled
    }

    /** Applied by the sync service after pulling the shared profile-settings row. */
    internal fun applyFromRemote(streamEnabled: Boolean?, subtitleEnabled: Boolean?) {
        ensureLoaded()
        var changed = false
        streamEnabled?.let {
            if (streamProviderEnabled != it) {
                streamProviderEnabled = it
                BuiltInProvidersStorage.saveStreamProviderEnabled(it)
                changed = true
            }
        }
        subtitleEnabled?.let {
            if (subtitleProviderEnabled != it) {
                subtitleProviderEnabled = it
                BuiltInProvidersStorage.saveSubtitleProviderEnabled(it)
                changed = true
            }
        }
        if (changed) publish()
    }

    private fun publish() {
        _uiState.value = BuiltInProvidersUiState(
            streamProviderEnabled = streamProviderEnabled,
            subtitleProviderEnabled = subtitleProviderEnabled,
        )
    }
}
