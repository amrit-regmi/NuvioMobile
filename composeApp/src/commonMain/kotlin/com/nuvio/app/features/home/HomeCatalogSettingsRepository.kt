package com.nuvio.app.features.home

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

data class HomeCatalogSettingsItem(
    val key: String,
    val defaultTitle: String,
    val addonName: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
    val isCollection: Boolean = false,
    val collectionId: String? = null,
    val isPinnedToTop: Boolean = false,
    val isReco: Boolean = false,
) {
    val displayTitle: String
        get() = customTitle.ifBlank { defaultTitle }
}

data class HomeCatalogSettingsUiState(
    val heroEnabled: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val hideCatalogUnderline: Boolean = false,
    // Cross-platform master toggles (shared with TV via `nuvio_home_catalog_settings`).
    val useBuiltinCatalog: Boolean = true,
    val useRecommendations: Boolean = true,
    val items: List<HomeCatalogSettingsItem> = emptyList(),
) {
    val signature: String
        get() = buildString {
            append(heroEnabled)
            append('|')
            append(hideUnreleasedContent)
            append('|')
            append(hideCatalogUnderline)
            append('|')
            append(useBuiltinCatalog)
            append('|')
            append(useRecommendations)
            append('|')
            append(
                items.joinToString(separator = "|") { item ->
                    "${item.key}:${item.order}:${item.enabled}:${item.heroSourceEnabled}:${item.customTitle}"
                }
            )
        }
}

internal data class HomeCatalogPreference(
    val customTitle: String,
    val enabled: Boolean,
    val heroSourceEnabled: Boolean,
    val order: Int,
)

internal data class HomeCatalogSettingsSnapshot(
    val heroEnabled: Boolean,
    val hideUnreleasedContent: Boolean,
    val hideCatalogUnderline: Boolean,
    val useBuiltinCatalog: Boolean,
    val useRecommendations: Boolean,
    val preferences: Map<String, HomeCatalogPreference>,
)

@Serializable
private data class StoredHomeCatalogPreference(
    val key: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
)

@Serializable
private data class StoredHomeCatalogSettingsPayload(
    val heroEnabled: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val hideCatalogUnderline: Boolean = false,
    val useBuiltinCatalog: Boolean = true,
    val useRecommendations: Boolean = true,
    val items: List<StoredHomeCatalogPreference> = emptyList(),
)

object HomeCatalogSettingsRepository {
    const val HERO_SOURCE_SELECTION_LIMIT = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(HomeCatalogSettingsUiState())
    val uiState: StateFlow<HomeCatalogSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var definitions: List<HomeCatalogDefinition> = emptyList()
    private var recoDefinitions: List<HomeCatalogDefinition> = emptyList()
    private var collectionDefinitions: List<CollectionCatalogDefinition> = emptyList()
    private var preferences: MutableMap<String, StoredHomeCatalogPreference> = mutableMapOf()
    private var heroEnabled = true
    private var hideUnreleasedContent = false
    private var hideCatalogUnderline = false
    private var useBuiltinCatalog = true
    private var useRecommendations = true

    /**
     * Reco row ordering and enabled state from TV's `rowOrder` (synced via [applyFromRemote]).
     * Keyed by `"${contentType}:${baseReasonType}"` (e.g. `"movie:personal"`), value is
     * `(order, enabled)`. Applied in [syncRecoRows] when actual reco rows arrive from `/reco`,
     * because we can't reconstruct their disambiguated keys (`personal_0`, etc.) before fetch.
     */
    private var rowOrderRecoPrefs: Map<String, Pair<Int, Boolean>> = emptyMap()

    fun onProfileChanged() {
        hasLoaded = false
        preferences.clear()
        heroEnabled = true
        hideUnreleasedContent = false
        hideCatalogUnderline = false
        useBuiltinCatalog = true
        useRecommendations = true
        definitions = emptyList()
        recoDefinitions = emptyList()
        collectionDefinitions = emptyList()
        rowOrderRecoPrefs = emptyMap()
        _uiState.value = HomeCatalogSettingsUiState()
    }

    fun clearLocalState() {
        hasLoaded = false
        definitions = emptyList()
        collectionDefinitions = emptyList()
        preferences.clear()
        heroEnabled = true
        hideUnreleasedContent = false
        hideCatalogUnderline = false
        useBuiltinCatalog = true
        useRecommendations = true
        rowOrderRecoPrefs = emptyMap()
        _uiState.value = HomeCatalogSettingsUiState()
    }

    fun syncCatalogs(addons: List<ManagedAddon>) {
        ensureLoaded()
        definitions = buildHomeCatalogDefinitions(addons)
        collectionDefinitions = buildCollectionDefinitions(CollectionRepository.collections.value)
        if (definitions.isEmpty() && recoDefinitions.isEmpty() && collectionDefinitions.isEmpty()) {
            publish()
            return
        }
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
    }

    /**
     * Registers the personalized recommendation rows (from `/reco`) so they appear in the
     * home catalog ordering alongside built-in catalog + collection rows. Reco rows are
     * never used as hero sources. Mirrors NuvioTV's reco-row merge into `rowOrder`.
     *
     * When [rowOrderRecoPrefs] is populated (from TV's `rowOrder` via [applyFromRemote]),
     * the TV-assigned order and enabled state are applied to the newly registered reco rows.
     * The lookup is by `"${contentType}:${baseReasonType}"` (without the `_index` suffix).
     */
    fun syncRecoRows(recoRows: List<RecoRow>) {
        ensureLoaded()
        recoDefinitions = recoRows.map { row -> row.toHomeCatalogDefinition() }
        // Apply TV-authored reco row ordering when rowOrderRecoPrefs is available.
        // The reco row key is "reco_engine:${contentType}:${reasonType_index}" (e.g.
        // "reco_engine:movie:personal_0"). The TV rowOrder stores just the base reason_type
        // (e.g. "personal"), so we strip the trailing "_<index>" to match.
        if (rowOrderRecoPrefs.isNotEmpty()) {
            for (row in recoRows) {
                val baseReasonType = row.reasonType.substringBeforeLast('_')
                val lookupKey = "${row.contentType.orEmpty()}:$baseReasonType"
                // Rows absent from rowOrder are disabled — TV's rowOrder is the authority.
                val (order, enabled) = rowOrderRecoPrefs[lookupKey] ?: Pair(Int.MAX_VALUE, false)
                val rowKey = row.key
                preferences[rowKey] = StoredHomeCatalogPreference(
                    key = rowKey,
                    customTitle = preferences[rowKey]?.customTitle.orEmpty(),
                    enabled = enabled,
                    heroSourceEnabled = false,
                    order = order,
                )
            }
        }
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun syncCollections(collections: List<Collection>) {
        ensureLoaded()
        collectionDefinitions = buildCollectionDefinitions(collections)
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    internal fun snapshot(): HomeCatalogSettingsSnapshot {
        ensureLoaded()
        return HomeCatalogSettingsSnapshot(
            heroEnabled = heroEnabled,
            hideUnreleasedContent = hideUnreleasedContent,
            hideCatalogUnderline = hideCatalogUnderline,
            useBuiltinCatalog = useBuiltinCatalog,
            useRecommendations = useRecommendations,
            preferences = preferences.mapValues { (_, value) ->
                HomeCatalogPreference(
                    customTitle = value.customTitle,
                    enabled = value.enabled,
                    heroSourceEnabled = value.heroSourceEnabled,
                    order = value.order,
                )
            },
        )
    }

    fun setHeroEnabled(enabled: Boolean) {
        ensureLoaded()
        heroEnabled = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun setHideUnreleasedContent(enabled: Boolean) {
        ensureLoaded()
        if (hideUnreleasedContent == enabled) return
        hideUnreleasedContent = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun setHideCatalogUnderline(enabled: Boolean) {
        ensureLoaded()
        if (hideCatalogUnderline == enabled) return
        hideCatalogUnderline = enabled
        publish()
        persist()
    }

    /** Built-in catalog provider master toggle (gates the catalog rows + meta/enrichment). */
    fun setUseBuiltinCatalog(enabled: Boolean) {
        ensureLoaded()
        if (useBuiltinCatalog == enabled) return
        useBuiltinCatalog = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    /** Recommendations master toggle (gates the personalized reco rows). */
    fun setUseRecommendations(enabled: Boolean) {
        ensureLoaded()
        if (useRecommendations == enabled) return
        useRecommendations = enabled
        publish()
        persist()
        // Re-fetch reco rows when turning ON (they were cleared when turned OFF); when OFF,
        // refreshRecommendations clears the cached reco rows. Either way it republishes Home.
        HomeRepository.refreshRecommendations(force = true)
    }

    fun setHeroSourceEnabled(key: String, enabled: Boolean) {
        updatePreference(key) { preference ->
            if (!enabled) {
                preference.copy(heroSourceEnabled = false)
            } else if (selectedHeroSourceCount(excludingKey = key) >= HERO_SOURCE_SELECTION_LIMIT) {
                preference
            } else {
                preference.copy(heroSourceEnabled = true)
            }
        }
    }

    fun setEnabled(key: String, enabled: Boolean) {
        updatePreference(key) { preference ->
            preference.copy(enabled = enabled)
        }
    }

    fun setCustomTitle(key: String, title: String) {
        updatePreference(key) { preference ->
            preference.copy(customTitle = title)
        }
    }

    fun resetToDefaults() {
        ensureLoaded()
        heroEnabled = true
        hideUnreleasedContent = false
        hideCatalogUnderline = false
        useBuiltinCatalog = true
        useRecommendations = true
        preferences.clear()
        rowOrderRecoPrefs = emptyMap()
        normalizePreferences()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun moveUp(key: String) {
        move(key = key, direction = -1)
    }

    fun moveDown(key: String) {
        move(key = key, direction = 1)
    }

    fun moveByIndex(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        val allKeys = allOrderedKeys()
        if (allKeys.isEmpty()) return
        if (fromIndex !in allKeys.indices || toIndex !in allKeys.indices) return
        if (fromIndex == toIndex) return
        val orderedKeys = allKeys.toMutableList()
        orderedKeys.add(toIndex, orderedKeys.removeAt(fromIndex))
        orderedKeys.forEachIndexed { index, itemKey ->
            val current = preferences[itemKey] ?: return@forEachIndexed
            preferences[itemKey] = current.copy(order = index)
        }
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    private fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = HomeCatalogSettingsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return

        val parsedPayload = runCatching {
            json.decodeFromString<StoredHomeCatalogSettingsPayload>(payload)
        }.getOrNull()

        if (parsedPayload != null) {
            heroEnabled = parsedPayload.heroEnabled
            hideUnreleasedContent = parsedPayload.hideUnreleasedContent
            hideCatalogUnderline = parsedPayload.hideCatalogUnderline
            useBuiltinCatalog = parsedPayload.useBuiltinCatalog
            useRecommendations = parsedPayload.useRecommendations
            preferences = parsedPayload.items.associateBy { it.key }.toMutableMap()
            publish()
            return
        }

        val legacyItems = runCatching {
            json.decodeFromString<List<StoredHomeCatalogPreference>>(payload)
        }.getOrDefault(emptyList())

        preferences = legacyItems.associateBy { it.key }.toMutableMap()
        publish()
    }

    private fun normalizePreferences() {
        val current = preferences
        data class UnifiedEntry(val key: String, val isCollection: Boolean, val isReco: Boolean = false)
        val catalogEntries = definitions.map { UnifiedEntry(it.key, false) }
        val recoEntries = recoDefinitions.map { UnifiedEntry(it.key, false, isReco = true) }
        val collectionEntries = collectionDefinitions.map { UnifiedEntry(it.key, true) }
        val allEntries = catalogEntries + recoEntries + collectionEntries
        val knownKeys = allEntries.mapTo(linkedSetOf(), UnifiedEntry::key)
        var nextOrder = (current.values.maxOfOrNull(StoredHomeCatalogPreference::order) ?: -1) + 1

        val orderedEntries = allEntries.mapIndexed { defaultIndex, entry ->
            Triple(
                entry,
                current[entry.key]?.order ?: (nextOrder + defaultIndex),
                defaultIndex,
            )
        }.sortedWith(
            compareBy<Triple<UnifiedEntry, Int, Int>>(
                { it.second },
                { it.third },
            ),
        ).map { it.first }

        val normalized = current
            .filterKeys { it !in knownKeys }
            .toMutableMap()
        var enabledHeroSourceCount = 0
        orderedEntries.forEach { entry ->
            val stored = current[entry.key]
            val heroSourceEnabled = if (entry.isCollection || entry.isReco) {
                false
            } else {
                (stored?.heroSourceEnabled ?: true) &&
                    enabledHeroSourceCount < HERO_SOURCE_SELECTION_LIMIT
            }
            if (heroSourceEnabled) {
                enabledHeroSourceCount += 1
            }
            normalized[entry.key] = StoredHomeCatalogPreference(
                key = entry.key,
                customTitle = stored?.customTitle.orEmpty(),
                enabled = stored?.enabled ?: true,
                heroSourceEnabled = heroSourceEnabled,
                order = stored?.order ?: nextOrder++,
            )
        }
        preferences = normalized
    }

    private fun publish() {
        val collectionMap = collectionDefinitions.associateBy { it.key }
        val catalogItems = (definitions + recoDefinitions)
            .map { definition ->
                val preference = preferences[definition.key]
                HomeCatalogSettingsItem(
                    key = definition.key,
                    defaultTitle = definition.defaultTitle,
                    addonName = definition.addonName,
                    customTitle = preference?.customTitle.orEmpty(),
                    enabled = preference?.enabled ?: true,
                    heroSourceEnabled = if (definition.isReco) false else (preference?.heroSourceEnabled ?: true),
                    order = preference?.order ?: 0,
                    isReco = definition.isReco,
                )
            }

        val collectionItems = collectionDefinitions.map { colDef ->
            val preference = preferences[colDef.key]
            HomeCatalogSettingsItem(
                key = colDef.key,
                defaultTitle = colDef.title,
                addonName = colDef.subtitle,
                customTitle = preference?.customTitle.orEmpty(),
                enabled = preference?.enabled ?: true,
                heroSourceEnabled = false,
                order = preference?.order ?: 0,
                isCollection = true,
                collectionId = colDef.collectionId,
                isPinnedToTop = colDef.isPinnedToTop,
            )
        }

        val items = (catalogItems + collectionItems)
            // Defensive dedupe: the home renders one Compose item per key, so a duplicate key
            // would both duplicate a row and crash the lazy list. Keep the first occurrence.
            .distinctBy { it.key }
            .sortedBy { it.order }

        _uiState.value = HomeCatalogSettingsUiState(
            heroEnabled = heroEnabled,
            hideUnreleasedContent = hideUnreleasedContent,
            hideCatalogUnderline = hideCatalogUnderline,
            useBuiltinCatalog = useBuiltinCatalog,
            useRecommendations = useRecommendations,
            items = items,
        )
    }

    private fun persist() {
        HomeCatalogSettingsStorage.savePayload(
            json.encodeToString(
                StoredHomeCatalogSettingsPayload(
                    heroEnabled = heroEnabled,
                    hideUnreleasedContent = hideUnreleasedContent,
                    hideCatalogUnderline = hideCatalogUnderline,
                    useBuiltinCatalog = useBuiltinCatalog,
                    useRecommendations = useRecommendations,
                    items = preferences.values.sortedBy { it.order },
                ),
            ),
        )
    }

    private fun updatePreference(
        key: String,
        transform: (StoredHomeCatalogPreference) -> StoredHomeCatalogPreference,
    ) {
        ensureLoaded()
        val current = preferences[key] ?: return
        preferences[key] = transform(current)
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    private fun selectedHeroSourceCount(excludingKey: String? = null): Int {
        val catalogKeys = definitions.mapTo(mutableSetOf()) { it.key }
        return preferences.count { (itemKey, preference) ->
            itemKey != excludingKey && itemKey in catalogKeys && preference.heroSourceEnabled
        }
    }

    private fun move(
        key: String,
        direction: Int,
    ) {
        ensureLoaded()
        val orderedKeys = allOrderedKeys().toMutableList()
        if (orderedKeys.isEmpty()) return

        val currentIndex = orderedKeys.indexOf(key)
        if (currentIndex == -1) return

        val targetIndex = currentIndex + direction
        if (targetIndex !in orderedKeys.indices) return

        val movingKey = orderedKeys.removeAt(currentIndex)
        orderedKeys.add(targetIndex, movingKey)

        orderedKeys.forEachIndexed { index, itemKey ->
            val current = preferences[itemKey] ?: return@forEachIndexed
            preferences[itemKey] = current.copy(order = index)
        }

        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun exportToSyncPayload(): SyncHomeCatalogPayload {
        ensureLoaded()
        // Reco rows are derived per-session from `/reco`; only their master toggle is synced,
        // not individual reco-row preferences — so exclude reco keys from the catalog item list.
        val recoKeys = recoDefinitions.mapTo(mutableSetOf()) { it.key }
        val items = preferences.values
            .filter { it.key !in recoKeys && !it.key.startsWith("${RECO_ADDON_ID}:") }
            .sortedBy { it.order }
            .map { pref ->
            val parts = pref.key.split(":")
            val isCollection = pref.key.startsWith("collection_")
            if (isCollection) {
                SyncCatalogItem(
                    addonId = "",
                    type = "",
                    catalogId = "",
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
                    isCollection = true,
                    collectionId = pref.key.removePrefix("collection_"),
                )
            } else {
                SyncCatalogItem(
                    addonId = parts.getOrElse(0) { "" },
                    type = parts.getOrElse(1) { "" },
                    catalogId = parts.getOrElse(2) { "" },
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
                    isCollection = false,
                )
            }
        }

        // Build rowOrder from the current ordered preference set (builtin + reco rows).
        // This lets the mobile write back in the TV-compatible format so a TV pull sees
        // the correct ordering. Collections are excluded (mobile-only concept).
        val builtinPrefix = "community.hamrocinema-catalog:"
        val allRows = mutableListOf<Pair<Int, RowOrderEntry>>()
        for (pref in preferences.values) {
            when {
                pref.key.startsWith(builtinPrefix) -> {
                    val rest = pref.key.removePrefix(builtinPrefix)
                    val parts = rest.split(":")
                    if (parts.size >= 2) {
                        allRows += Pair(
                            pref.order,
                            RowOrderEntry(
                                id = parts[1],
                                kind = "builtin",
                                type = parts[0],
                                enabled = pref.enabled,
                            ),
                        )
                    }
                }
                pref.key.startsWith("${RECO_ADDON_ID}:") -> {
                    val rest = pref.key.removePrefix("${RECO_ADDON_ID}:")
                    val parts = rest.split(":")
                    if (parts.size >= 2) {
                        val baseReasonType = parts[1].substringBeforeLast('_')
                        allRows += Pair(
                            pref.order,
                            RowOrderEntry(
                                id = baseReasonType,
                                kind = "reco",
                                type = parts[0],
                                enabled = pref.enabled,
                            ),
                        )
                    }
                }
                // Collections skipped; addon rows skipped (not in preferences by uuid alone).
            }
        }
        val rowOrder = allRows.sortedBy { it.first }.map { it.second }

        return SyncHomeCatalogPayload(
            hideUnreleasedContent = hideUnreleasedContent,
            hideCatalogUnderline = hideCatalogUnderline,
            useBuiltinCatalog = useBuiltinCatalog,
            useRecommendations = useRecommendations,
            items = items,
            rowOrder = rowOrder,
        )
    }

    fun applyFromRemote(payload: SyncHomeCatalogPayload) {
        ensureLoaded()
        hideUnreleasedContent = payload.hideUnreleasedContent
        hideCatalogUnderline = payload.hideCatalogUnderline
        // Adopt the TV-shared master toggles only when the remote row actually carries them.
        payload.useBuiltinCatalog?.let { useBuiltinCatalog = it }
        payload.useRecommendations?.let { useRecommendations = it }

        if (payload.rowOrder.isNotEmpty()) {
            // TV-authoritative format: rowOrder supersedes `items`.
            // Process builtin and addon rows directly into preferences; reco rows are stashed
            // in rowOrderRecoPrefs and applied later in syncRecoRows when /reco rows arrive.
            val existingHeroState = preferences.mapValues { it.value.heroSourceEnabled }
            val newPrefs = preferences.toMutableMap()
            val newRecoPrefs = mutableMapOf<String, Pair<Int, Boolean>>()

            payload.rowOrder.forEachIndexed { index, entry ->
                when (entry.kind) {
                    "builtin" -> {
                        // Built-in catalog-addon key: "<manifest_id>:<type>:<catalog_id>"
                        val key = "community.hamrocinema-catalog:${entry.type}:${entry.id}"
                        newPrefs[key] = StoredHomeCatalogPreference(
                            key = key,
                            customTitle = newPrefs[key]?.customTitle.orEmpty(),
                            enabled = entry.enabled,
                            heroSourceEnabled = existingHeroState[key] ?: true,
                            order = index,
                        )
                    }
                    "reco" -> {
                        // Reco rows are fetched dynamically from /reco; their actual keys
                        // include a disambiguating index suffix (e.g. "personal_0"). Store the
                        // TV order/enabled keyed by "${contentType}:${baseReasonType}" so
                        // syncRecoRows() can match them when the rows arrive.
                        val lookupKey = "${entry.type}:${entry.id}"
                        newRecoPrefs[lookupKey] = Pair(index, entry.enabled)
                    }
                    "addon" -> {
                        // Per-profile addon UUID: best-effort — skip if we can't reconstruct
                        // the full "addonId:type:catalogId" key without a manifest lookup.
                        // Addon ordering from the TV is rare and not critical for this fix.
                    }
                    // "collection" rows are owned by the mobile; skip to preserve local ordering.
                }
            }
            rowOrderRecoPrefs = newRecoPrefs
            preferences = newPrefs
        } else if (payload.items.isNotEmpty()) {
            // Legacy mobile format: items array (no reco rows, no rowOrder).
            val existingHeroState = preferences.mapValues { it.value.heroSourceEnabled }
            preferences = payload.items.associate { item ->
                val key = if (item.isCollection) {
                    "collection_${item.collectionId}"
                } else {
                    "${item.addonId}:${item.type}:${item.catalogId}"
                }
                key to StoredHomeCatalogPreference(
                    key = key,
                    customTitle = item.customTitle,
                    enabled = item.enabled,
                    heroSourceEnabled = existingHeroState[key] ?: true,
                    order = item.order,
                )
            }.toMutableMap()
        }
        hasLoaded = true
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    private fun allOrderedKeys(): List<String> {
        val catalogKeys = definitions.map { it.key }
        val recoKeys = recoDefinitions.map { it.key }
        val collectionKeys = collectionDefinitions.map { it.key }
        return (catalogKeys + recoKeys + collectionKeys)
            .sortedBy { key -> preferences[key]?.order ?: Int.MAX_VALUE }
    }

    private fun enforcePinnedCollectionsAtTop() {
        val orderedKeys = allOrderedKeys()
        if (orderedKeys.isEmpty()) return

        val pinnedCollectionKeys = collectionDefinitions
            .asSequence()
            .filter { it.isPinnedToTop }
            .map { it.key }
            .toSet()
        if (pinnedCollectionKeys.isEmpty()) return

        val pinnedKeys = orderedKeys.filter { it in pinnedCollectionKeys }
        if (pinnedKeys.isEmpty()) return

        val nonPinnedKeys = orderedKeys.filterNot { it in pinnedCollectionKeys }
        val reorderedKeys = pinnedKeys + nonPinnedKeys
        if (reorderedKeys == orderedKeys) return

        reorderedKeys.forEachIndexed { index, itemKey ->
            val current = preferences[itemKey] ?: return@forEachIndexed
            preferences[itemKey] = current.copy(order = index)
        }
    }
}

internal data class CollectionCatalogDefinition(
    val key: String,
    val collectionId: String,
    val title: String,
    val subtitle: String,
    val isPinnedToTop: Boolean,
)

internal fun buildCollectionDefinitions(collections: List<Collection>): List<CollectionCatalogDefinition> =
    collections.filter { it.folders.isNotEmpty() }.map { collection ->
        CollectionCatalogDefinition(
            key = "collection_${collection.id}",
            collectionId = collection.id,
            title = collection.title,
            subtitle = runBlocking { getString(Res.string.collections_folder_count, collection.folders.size) },
            isPinnedToTop = collection.pinToTop,
        )
    }
