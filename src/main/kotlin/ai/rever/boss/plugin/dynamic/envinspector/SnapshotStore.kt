package ai.rever.boss.plugin.dynamic.envinspector

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * In-memory list of saved snapshots, capped at [MAX_SNAPSHOTS]. Persisted to
 * the plugin's own [PluginStorageProvider] under [STORAGE_KEY] so a user's
 * snapshots survive a restart, and loaded back on first access.
 *
 * New saves evict the oldest snapshot. This is the only retention rule; a
 * snapshot store with no bound would grow without limit and would make the
 * diff tool's "compare to N snapshots ago" feature quietly expensive.
 *
 * Thread-safety: all mutations go through a single coroutine scope on the
 * caller's side; the underlying [MutableStateFlow] is read by the UI on the
 * Compose thread, which is fine because StateFlow is documented as safe for
 * concurrent reads.
 */
class SnapshotStore(
    private val storage: PluginStorageProvider,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _snapshots = MutableStateFlow<List<EnvSnapshot>>(emptyList())
    val snapshots: StateFlow<List<EnvSnapshot>> = _snapshots.asStateFlow()

    /**
     * Load persisted snapshots once. Subsequent saves overwrite the same
     * key. Called from [EnvInspectorDynamicPlugin.register]; safe to call
     * before the UI subscribes because [StateFlow.value] returns the latest
     * known state.
     */
    suspend fun load() {
        val raw = storage.getJson(STORAGE_KEY) ?: return
        val parsed = runCatching { json.decodeFromString<List<EnvSnapshot>>(raw) }
            .getOrElse { return }
        // Defensive cap on read: a hand-edited storage file could carry
        // more than the in-memory bound; trim to MAX_SNAPSHOTS so a corrupt
        // file cannot blow out memory on next launch.
        _snapshots.value = parsed.take(MAX_SNAPSHOTS)
    }

    /**
     * Add [snapshot] to the front of the list. The list is trimmed to
     * [MAX_SNAPSHOTS] entries - the oldest entry is dropped on overflow.
     * Triggers a single persist call.
     */
    suspend fun save(snapshot: EnvSnapshot) {
        val next = (listOf(snapshot) + _snapshots.value).take(MAX_SNAPSHOTS)
        _snapshots.value = next
        persist(next)
    }

    /**
     * Remove a snapshot by id. No-op if the id is unknown; persisted state
     * is only rewritten when an entry actually changes, so an idempotent
     * delete does not cost a write.
     */
    suspend fun delete(id: String) {
        val next = _snapshots.value.filterNot { it.id == id }
        if (next.size == _snapshots.value.size) return
        _snapshots.value = next
        persist(next)
    }

    fun findById(id: String): EnvSnapshot? = _snapshots.value.firstOrNull { it.id == id }

    private suspend fun persist(list: List<EnvSnapshot>) {
        runCatching { storage.putJson(STORAGE_KEY, json.encodeToString(list)) }
    }

    companion object {
        const val MAX_SNAPSHOTS: Int = 10
        const val STORAGE_KEY: String = "env_inspector.snapshots.v1"
    }
}
