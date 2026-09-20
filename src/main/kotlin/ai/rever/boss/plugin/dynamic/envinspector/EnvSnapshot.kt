package ai.rever.boss.plugin.dynamic.envinspector

import kotlinx.serialization.Serializable

/**
 * One observation of a single environment entry.
 *
 * [value] is the **displayed** value - secret entries are stored already masked
 * here, so the snapshot on disk and in memory never carries a real secret
 * unless the snapshot was taken through the explicit reveal path. The MCP
 * reveal tool is the only path that lets an agent see a real value, and the
 * call is recorded in the host's MCP ledger.
 *
 * [source] is human-readable: "system property", "process env", or "boss.runtime".
 */
@Serializable
data class EnvEntry(
    val key: String,
    val value: String,
    val source: String,
)

/**
 * Categories of entry shown in the panel. Each is bound to
 * [MAX_ENTRIES_PER_CATEGORY] entries so a hostile host cannot make the
 * collector allocate gigabytes per category.
 */
@Serializable
enum class EnvCategory(val label: String, val sourceLabel: String) {
    SystemProperties("System properties", "system property"),
    EnvironmentVariables("Environment variables", "process env"),
    BossSettings("BOSS runtime", "boss.runtime"),
}

/**
 * One captured snapshot - the whole collector output at a moment in time.
 * Used by [SnapshotStore] to bound in-memory state and by the diff MCP tool
 * to answer "what changed?".
 */
@Serializable
data class EnvSnapshot(
    val id: String,
    val label: String,
    val createdAtEpochMs: Long,
    val entries: List<EnvEntry>,
) {
    fun forCategory(category: EnvCategory): List<EnvEntry> =
        entries.filter { it.source == category.sourceLabel }
}

/**
 * Diff between two snapshots, keyed by entry key. Entries present in both and
 * equal are omitted; the panel and MCP tool only list what actually changed.
 */
@Serializable
data class EnvDiff(
    val added: List<EnvEntry>,
    val removed: List<EnvEntry>,
    val changed: List<EnvDiffChange>,
)

@Serializable
data class EnvDiffChange(
    val key: String,
    val source: String,
    val before: String,
    val after: String,
)
