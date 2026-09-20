package ai.rever.boss.plugin.dynamic.envinspector

import ai.rever.boss.plugin.api.ClipboardProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * State holder for the Env Inspector panel.
 *
 * Filter, search and snapshot list are all exposed as [StateFlow] so the
 * Compose tree recomposes only when something the user can see changes. The
 * collector itself is stateless - it reads process state on demand - so we
 * just call it whenever something the user can do (refresh / save snapshot)
 * needs fresh data.
 */
class EnvInspectorViewModel(
    private val collector: EnvCollector,
    private val snapshotStoreSupplier: () -> SnapshotStore?,
    private val clipboardProviderSupplier: () -> ClipboardProvider?,
    private val pluginScope: CoroutineScope,
) {

    // ---- filter / search / selection state -------------------------

    private val _selectedCategory = MutableStateFlow<EnvCategory?>(null)
    val selectedCategory: StateFlow<EnvCategory?> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _currentEntries = MutableStateFlow<Map<EnvCategory, List<EnvEntry>>>(
        EnvCategory.values().associateWith { emptyList() },
    )

    /** Cached snapshot list from [SnapshotStore.snapshots]. */
    val snapshots: StateFlow<List<EnvSnapshot>> = snapshotStoreSupplier()
        ?.snapshots
        ?: MutableStateFlow(emptyList())

    /**
     * Entries the UI actually renders: filtered by category, then by
     * search. Search is case-insensitive substring against key or source;
     * the value side is excluded because the user's mental model is "name
     * of the setting" not "what its value looks like".
     */
    val visibleEntries: StateFlow<List<EnvEntry>> = combine(
        _currentEntries,
        _selectedCategory,
        _searchQuery,
    ) { byCategory, category, query ->
        val pool: List<EnvEntry> = if (category == null) {
            // No category selected: show everything, in declaration order.
            EnvCategory.values().flatMap { byCategory[it].orEmpty() }
        } else {
            byCategory[category].orEmpty()
        }
        if (query.isBlank()) {
            pool
        } else {
            val needle = query.trim().lowercase()
            pool.filter {
                it.key.lowercase().contains(needle) ||
                    it.source.lowercase().contains(needle)
            }
        }
    }.stateIn(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    init {
        refresh()
        // Reflect snapshot list changes into the UI without the panel
        // having to subscribe manually. No-op when storage is null.
        snapshotStoreSupplier()?.snapshots?.let { flow ->
            // Already exposed as `snapshots`; nothing extra to wire.
            // Read-side subscription is the panel's responsibility.
            @Suppress("UNUSED_VARIABLE") val unused = flow
        }
    }

    // ---- user actions ----------------------------------------------

    /** Re-read process state. Cheap; the collector is just three reads. */
    fun refresh() {
        runCatching { collector.snapshot() }
            .onSuccess { _currentEntries.value = it }
            .onFailure { _errorMessage.value = "Failed to read environment: ${it.message}" }
    }

    fun setCategory(category: EnvCategory?) {
        _selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Copy [entry]'s value to the clipboard. For masked entries this copies
     * the masked token, never the real value - the panel never sees real
     * secrets, so the clipboard paste will yield `<masked>`.
     */
    fun copyValue(entry: EnvEntry) {
        val cb = clipboardProviderSupplier() ?: run {
            _errorMessage.value = "Clipboard not available"
            return
        }
        val ok = runCatching { cb.setText(entry.value) }.getOrDefault(false)
        if (ok) {
            _statusMessage.value = "Copied: ${entry.key}"
        } else {
            _errorMessage.value = "Clipboard write failed"
        }
    }

    /**
     * Snapshot the current state and persist it through [SnapshotStore].
     * The label is auto-generated so a user can save many without naming
     * each one; the id is a UUID so two saves in the same millisecond do
     * not collide.
     */
    fun saveSnapshot() {
        val store = snapshotStoreSupplier() ?: run {
            _errorMessage.value = "Storage not available"
            return
        }
        val entries = _currentEntries.value.values.flatten()
        val snapshot = EnvSnapshot(
            id = UUID.randomUUID().toString(),
            label = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.ROOT,
            ).format(java.util.Date()),
            createdAtEpochMs = System.currentTimeMillis(),
            entries = entries,
        )
        pluginScope.launch {
            runCatching { store.save(snapshot) }
                .onSuccess { _statusMessage.value = "Snapshot saved" }
                .onFailure { _errorMessage.value = "Save failed: ${it.message}" }
        }
    }

    fun deleteSnapshot(id: String) {
        val store = snapshotStoreSupplier() ?: return
        pluginScope.launch {
            runCatching { store.delete(id) }
        }
    }

    fun clearMessages() {
        _statusMessage.value = null
        _errorMessage.value = null
    }
}
