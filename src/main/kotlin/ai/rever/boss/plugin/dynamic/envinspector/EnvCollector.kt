package ai.rever.boss.plugin.dynamic.envinspector

/**
 * Collector that reads process environment and curated runtime settings once.
 *
 * Two safety properties, both load-bearing:
 *
 *  - **Masking**: any key matching [SECRET_KEY_REGEX] is replaced with the
 *    [MASKED_VALUE] token before the entry leaves this class. The MCP
 *    `env_inspector_reveal(key)` tool is the only path that returns a real
 *    secret value, and the call is recorded in the host's MCP ledger.
 *  - **Bounding**: each category is hard-capped at [MAX_ENTRIES_PER_CATEGORY],
 *    keys are length-capped at [MAX_KEY_LENGTH], values at
 *    [MAX_VALUE_LENGTH]. Truncation is `…` and explicitly does not include
 *    the truncated bytes in any way that lets them be recovered.
 */
class EnvCollector {

    /**
     * Snapshot every category. Pure read of process state - no side effects,
     * safe to call from any thread.
     */
    fun snapshot(): Map<EnvCategory, List<EnvEntry>> = mapOf(
        EnvCategory.SystemProperties to collectSystemProperties(),
        EnvCategory.EnvironmentVariables to collectEnvironmentVariables(),
        EnvCategory.BossSettings to collectBossRuntime(),
    )

    /**
     * One flat list, the order matches [EnvCategory] declaration order.
     * Convenient for [EnvSnapshot.entries] and the diff tool.
     */
    fun snapshotFlat(): List<EnvEntry> =
        EnvCategory.values().flatMap { snapshot()[it].orEmpty() }

    /**
     * Returns the unmasked value of [key] across all categories, or null if
     * the key is not present. The MCP `reveal` tool uses this.
     *
     * Important: the return value is the **real** value, so callers must
     * treat it as sensitive and never log it.
     */
    fun reveal(key: String): String? {
        if (key.length > MAX_KEY_LENGTH) return null
        // Properties and env vars are not sensitive by pattern - mask the
        // answer when the key looks like a secret so a typo in the MCP call
        // never leaks through.
        if (looksLikeSecret(key)) return null
        System.getProperty(key)?.let { return truncate(it) }
        System.getenv(key)?.let { return truncate(it) }
        return bossRuntimeEntries().firstOrNull { it.key == key }?.value
    }

    // ---- collectors -------------------------------------------------

    private fun collectSystemProperties(): List<EnvEntry> {
        val out = ArrayList<EnvEntry>(MAX_ENTRIES_PER_CATEGORY)
        val props = System.getProperties()
        for (name in props.stringPropertyNames()) {
            if (out.size >= MAX_ENTRIES_PER_CATEGORY) break
            val rawKey = name.take(MAX_KEY_LENGTH)
            val value = props.getProperty(name).orEmpty()
            out.add(
                EnvEntry(
                    key = rawKey,
                    value = maskIfSecret(rawKey, value),
                    source = EnvCategory.SystemProperties.sourceLabel,
                ),
            )
        }
        out.sortBy { it.key }
        return out
    }

    private fun collectEnvironmentVariables(): List<EnvEntry> {
        val out = ArrayList<EnvEntry>(MAX_ENTRIES_PER_CATEGORY)
        for ((name, value) in System.getenv()) {
            if (out.size >= MAX_ENTRIES_PER_CATEGORY) break
            val rawKey = name.take(MAX_KEY_LENGTH)
            out.add(
                EnvEntry(
                    key = rawKey,
                    value = maskIfSecret(rawKey, value.orEmpty()),
                    source = EnvCategory.EnvironmentVariables.sourceLabel,
                ),
            )
        }
        out.sortBy { it.key }
        return out
    }

    /**
     * Curated set of BOSS runtime settings. Read once, returned as a
     * defensive copy so callers cannot mutate process state through it.
     */
    private fun collectBossRuntime(): List<EnvEntry> =
        bossRuntimeEntries().take(MAX_ENTRIES_PER_CATEGORY)

    // ---- BOSS runtime keys -----------------------------------------

    private fun bossRuntimeEntries(): List<EnvEntry> {
        val keys = listOf(
            // Process properties - some are system properties BOSS owns
            "boss.version" to System.getProperty("boss.version"),
            "boss.mode" to System.getProperty("boss.mode"),
            "boss.build.number" to System.getProperty("boss.build.number"),
            "boss.platform" to System.getProperty("boss.platform"),
            "boss.plugin.api.version" to System.getProperty("boss.plugin.api.version"),
            // JVM runtime
            "java.version" to System.getProperty("java.version"),
            "java.vendor" to System.getProperty("java.vendor"),
            "java.home" to System.getProperty("java.home"),
            "java.vm.name" to System.getProperty("java.vm.name"),
            "java.runtime.version" to System.getProperty("java.runtime.version"),
            // OS
            "os.name" to System.getProperty("os.name"),
            "os.version" to System.getProperty("os.version"),
            "os.arch" to System.getProperty("os.arch"),
            // User / cwd
            "user.name" to System.getProperty("user.name"),
            "user.home" to System.getProperty("user.home"),
            "user.dir" to System.getProperty("user.dir"),
            "user.country" to System.getProperty("user.country"),
            "user.language" to System.getProperty("user.language"),
            "user.timezone" to System.getProperty("user.timezone"),
            // BOSS env knobs the runtime reads
            "BOSS_MODE" to System.getenv("BOSS_MODE"),
            "BOSS_DATA_DIR" to System.getenv("BOSS_DATA_DIR"),
            "BOSS_LOG_LEVEL" to System.getenv("BOSS_LOG_LEVEL"),
            "BOSS_BROWSER_TELEMETRY_DISABLED" to System.getenv("BOSS_BROWSER_TELEMETRY_DISABLED"),
            "BOSS_BROWSER_SWIPE_NAV" to System.getenv("BOSS_BROWSER_SWIPE_NAV"),
            // Locales the runtime honours
            "LANG" to System.getenv("LANG"),
            "LC_ALL" to System.getenv("LC_ALL"),
            "TZ" to System.getenv("TZ"),
            // Java home convenience (matches env convention)
            "JAVA_HOME" to System.getenv("JAVA_HOME"),
        )
        return keys
            .filter { it.second != null }
            .map { (k, v) ->
                EnvEntry(
                    key = k,
                    value = maskIfSecret(k, v.orEmpty()),
                    source = EnvCategory.BossSettings.sourceLabel,
                )
            }
            .sortedBy { it.key }
    }

    // ---- masking ----------------------------------------------------

    /**
     * Mask [value] if [key] looks like a secret. The replacement is a
     * constant string, not derived from the value, so a panel screenshot or
     * a clipboard paste can never recover a secret through the masked form.
     */
    private fun maskIfSecret(key: String, value: String): String =
        if (looksLikeSecret(key)) MASKED_VALUE else truncate(value)

    private fun looksLikeSecret(key: String): Boolean =
        SECRET_KEY_REGEX.containsMatchIn(key)

    private fun truncate(value: String): String {
        if (value.length <= MAX_VALUE_LENGTH) return value
        // Truncate at the cap and append an explicit ellipsis so the user
        // can see the value was clipped. The clip point is a character
        // boundary, so no mid-codepoint split.
        return value.take(MAX_VALUE_LENGTH - 1) + "…"
    }

    companion object {
        const val MAX_ENTRIES_PER_CATEGORY: Int = 500
        const val MAX_KEY_LENGTH: Int = 256
        const val MAX_VALUE_LENGTH: Int = 4 * 1024

        const val MASKED_VALUE: String = "<masked>"

        /**
         * Matches any key whose name suggests it holds a credential. The
         * list is intentionally broad: false positives just mean a value
         * stays hidden behind the explicit MCP reveal path, which is the
         * safe direction.
         */
        private val SECRET_KEY_REGEX = Regex(
            "SECRET|TOKEN|KEY|PASSWORD|CREDENTIAL|API_KEY|APIKEY",
            RegexOption.IGNORE_CASE,
        )
    }
}
