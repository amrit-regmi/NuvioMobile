package com.nuvio.app.features.settings

/**
 * Local (per-profile) persistence for the built-in provider toggles that live on the
 * shared `nuvio_profile_settings` Supabase row: the stream provider toggle and the
 * subtitle provider toggle. Their authoritative cross-platform value is mirrored to
 * Supabase by [BuiltInProvidersSettingsSyncService]; this is the offline-first cache.
 */
internal expect object BuiltInProvidersStorage {
    fun loadStreamProviderEnabled(): Boolean?
    fun saveStreamProviderEnabled(enabled: Boolean)
    fun loadSubtitleProviderEnabled(): Boolean?
    fun saveSubtitleProviderEnabled(enabled: Boolean)
}
