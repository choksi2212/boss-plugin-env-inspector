package ai.rever.boss.plugin.dynamic.envinspector

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult

/**
 * MCP tools exposed to in-terminal agents.
 *
 * Four tools, one purpose: answer "what does the BOSS process know about?"
 * without ever printing a real secret. The reveal tool is the only path
 * that can return one and is declared `readOnly = false` so the host's
 * approval surface treats it as a deliberate action and records it in the
 * MCP ledger.
 *
 * All tool responses are plain text (the host serialises them straight
 * back to the MCP client). JSON would be more parseable, but agents that
 * grep these by hand want one entry per line and the host already formats
 * larger responses the same way.
 */
internal class EnvInspectorMcpToolProvider(
    override val providerId: String,
    private val collector: EnvCollector,
    private val snapshotStoreSupplier: () -> SnapshotStore?,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "env_inspector_list",
            description = "List the BOSS process environment - system properties, " +
                "process env vars, and curated runtime settings. Optional category " +
                "argument narrows the result to one of: SystemProperties, " +
                "EnvironmentVariables, BossSettings. Secret-looking keys are returned " +
                "as <masked>; use env_inspector_reveal for the real value (recorded " +
                "in the host's MCP ledger).",
            inputSchema = LIST_SCHEMA,
            handler = McpToolHandler { args -> handleList(args) },
        ),
        McpToolDefinition(
            name = "env_inspector_get",
            description = "Read one entry by key across all categories. Returns the " +
                "displayed (already-masked) value or null when the key is not present. " +
                "Secret-looking keys return <masked>; use env_inspector_reveal to see " +
                "the real value.",
            inputSchema = KEY_SCHEMA,
            handler = McpToolHandler { args -> handleGet(args) },
        ),
        McpToolDefinition(
            name = "env_inspector_reveal",
            description = "Reveal the real value of a single entry whose name matches " +
                "the secret pattern. Use sparingly - this call is recorded in the " +
                "host's MCP ledger with the key, NOT the value. Refuses any key whose " +
                "name does not look like a secret; use env_inspector_get for those.",
            inputSchema = KEY_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args -> handleReveal(args) },
        ),
        McpToolDefinition(
            name = "env_inspector_diff",
            description = "Compare two saved snapshots by id. Returns added, removed, " +
                "and changed entries. The current process state is always available " +
                "as the snapshot id 'current'.",
            inputSchema = DIFF_SCHEMA,
            handler = McpToolHandler { args -> handleDiff(args) },
        ),
    )

    // ---- handlers --------------------------------------------------

    private fun handleList(args: McpToolArgs): McpToolResult {
        val snapshot = collector.snapshot()
        val requested = args.string("category")?.let { name ->
            runCatching { EnvCategory.valueOf(name) }.getOrNull()
        }
        if (args.has("category") && requested == null) {
            val known = EnvCategory.values().joinToString(", ") { it.name }
            return McpToolResult(
                "Unknown category. Expected one of: $known",
                isError = true,
            )
        }
        val out = StringBuilder()
        if (requested == null) {
            EnvCategory.values().forEach { cat ->
                appendCategory(out, cat, snapshot[cat].orEmpty())
            }
        } else {
            appendCategory(out, requested, snapshot[requested].orEmpty())
        }
        return McpToolResult(out.toString().trimEnd())
    }

    private fun appendCategory(out: StringBuilder, cat: EnvCategory, entries: List<EnvEntry>) {
        if (entries.isEmpty()) return
        out.append("[").append(cat.name).append("] (").append(entries.size).append(")\n")
        for (entry in entries) {
            out.append("  ").append(entry.key).append(" = ").append(entry.value)
                .append("  [").append(entry.source).append("]\n")
        }
    }

    private fun handleGet(args: McpToolArgs): McpToolResult {
        val key = args.string("key")
            ?: return McpToolResult("Missing required argument: key", isError = true)
        if (key.length > EnvCollector.MAX_KEY_LENGTH) {
            return McpToolResult("Key exceeds length limit", isError = true)
        }
        val snapshot = collector.snapshot()
        for ((_, entries) in snapshot) {
            val match = entries.firstOrNull { it.key == key }
            if (match != null) {
                return McpToolResult(
                    "${match.key} = ${match.value}  [${match.source}]",
                )
            }
        }
        return McpToolResult("Key not found: $key", isError = true)
    }

    private fun handleReveal(args: McpToolArgs): McpToolResult {
        val key = args.string("key")
            ?: return McpToolResult("Missing required argument: key", isError = true)
        if (key.length > EnvCollector.MAX_KEY_LENGTH) {
            return McpToolResult("Key exceeds length limit", isError = true)
        }
        // The reveal tool is for secret-looking keys. The agent could ask
        // for any key; if it doesn't match the pattern, we point at the
        // get tool rather than returning the value here, so the MCP ledger
        // has the right name attached to the call.
        if (!looksLikeSecret(key)) {
            return McpToolResult(
                "Key '$key' does not look like a secret. Use env_inspector_get for non-secret values.",
                isError = true,
            )
        }
        val value = collector.reveal(key)
            ?: return McpToolResult("Key not found: $key", isError = true)
        // Build the response without ever logging the value: callers and
        // the host ledger see only the key.
        return McpToolResult("$key = $value  [revealed]")
    }

    private fun handleDiff(args: McpToolArgs): McpToolResult {
        val a = args.string("snapshotA")
            ?: return McpToolResult("Missing required argument: snapshotA", isError = true)
        val b = args.string("snapshotB")
            ?: return McpToolResult("Missing required argument: snapshotB", isError = true)
        if (a.length > EnvCollector.MAX_KEY_LENGTH || b.length > EnvCollector.MAX_KEY_LENGTH) {
            return McpToolResult("Snapshot id exceeds length limit", isError = true)
        }
        val left = resolveSnapshot(a)
            ?: return McpToolResult("Unknown snapshot: $a", isError = true)
        val right = resolveSnapshot(b)
            ?: return McpToolResult("Unknown snapshot: $b", isError = true)
        val diff = computeDiff(left, right)
        return formatDiff(diff)
    }

    /**
     * Resolve an id like `"current"` or a real snapshot id. Anything that
     * is not `"current"` must exist in the snapshot store; an unknown id
     * is an error, not a silently-empty diff.
     */
    private fun resolveSnapshot(id: String): EnvSnapshot? {
        if (id == "current") {
            return EnvSnapshot(
                id = "current",
                label = "current",
                createdAtEpochMs = System.currentTimeMillis(),
                entries = collector.snapshotFlat(),
            )
        }
        return snapshotStoreSupplier()?.findById(id)
    }

    private fun formatDiff(diff: EnvDiff): McpToolResult {
        val out = StringBuilder()
        if (diff.added.isEmpty() && diff.removed.isEmpty() && diff.changed.isEmpty()) {
            return McpToolResult("No differences.")
        }
        if (diff.added.isNotEmpty()) {
            out.append("Added (").append(diff.added.size).append("):\n")
            for (e in diff.added) out.append("  + ").append(e.key).append(" = ").append(e.value)
                .append("  [").append(e.source).append("]\n")
        }
        if (diff.removed.isNotEmpty()) {
            out.append("Removed (").append(diff.removed.size).append("):\n")
            for (e in diff.removed) out.append("  - ").append(e.key).append(" = ").append(e.value)
                .append("  [").append(e.source).append("]\n")
        }
        if (diff.changed.isNotEmpty()) {
            out.append("Changed (").append(diff.changed.size).append("):\n")
            for (c in diff.changed) out.append("  ~ ").append(c.key).append(" : ")
                .append(c.before).append(" -> ").append(c.after)
                .append("  [").append(c.source).append("]\n")
        }
        return McpToolResult(out.toString().trimEnd())
    }

    private fun computeDiff(left: EnvSnapshot, right: EnvSnapshot): EnvDiff {
        val leftMap = left.entries.associateBy { it.source to it.key }
        val rightMap = right.entries.associateBy { it.source to it.key }

        val added = ArrayList<EnvEntry>()
        val removed = ArrayList<EnvEntry>()
        val changed = ArrayList<EnvDiffChange>()

        for ((k, r) in rightMap) {
            val l = leftMap[k]
            if (l == null) {
                added.add(r)
            } else if (l.value != r.value) {
                changed.add(
                    EnvDiffChange(
                        key = r.key,
                        source = r.source,
                        before = l.value,
                        after = r.value,
                    ),
                )
            }
        }
        for ((k, l) in leftMap) {
            if (rightMap[k] == null) removed.add(l)
        }
        // Stable, predictable order for callers diffing two reports.
        added.sortWith(compareBy({ it.source }, { it.key }))
        removed.sortWith(compareBy({ it.source }, { it.key }))
        changed.sortWith(compareBy({ it.source }, { it.key }))
        return EnvDiff(added = added, removed = removed, changed = changed)
    }

    private fun looksLikeSecret(key: String): Boolean =
        SECRET_KEY_REGEX.containsMatchIn(key)

    private companion object {
        const val LIST_SCHEMA =
            """{"type":"object","properties":{"category":{"type":"string","description":"Optional category: SystemProperties, EnvironmentVariables, or BossSettings."}}}"""
        const val KEY_SCHEMA =
            """{"type":"object","properties":{"key":{"type":"string","description":"Entry name (system property key, env var name, or BOSS runtime key)."}},"required":["key"]}"""
        const val DIFF_SCHEMA =
            """{"type":"object","properties":{"snapshotA":{"type":"string","description":"Snapshot id (uuid) or 'current'."},"snapshotB":{"type":"string","description":"Snapshot id (uuid) or 'current'."}},"required":["snapshotA","snapshotB"]}"""
        private val SECRET_KEY_REGEX = Regex(
            "SECRET|TOKEN|KEY|PASSWORD|CREDENTIAL|API_KEY|APIKEY",
            RegexOption.IGNORE_CASE,
        )
    }
}
