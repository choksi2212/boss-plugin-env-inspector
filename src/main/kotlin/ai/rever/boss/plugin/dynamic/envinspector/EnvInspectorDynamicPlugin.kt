package ai.rever.boss.plugin.dynamic.envinspector

import ai.rever.boss.plugin.api.ClipboardProvider
import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Entry point for the Env Inspector dynamic plugin.
 *
 * Owns the long-lived singletons the panel and MCP tools share:
 *
 *  - [EnvCollector] - reads process state; stateless and safe to share.
 *  - [SnapshotStore] - holds the persisted snapshot list; one per plugin
 *    instance, scoped to this plugin's storage.
 *
 * The clipboard provider is captured here so the panel does not have to
 * chase it back through [PluginContext]. The MCP tool provider is registered
 * in [register]; the host automatically removes it when the plugin is
 * disabled or unloaded.
 */
class EnvInspectorDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.envinspector"
    override val displayName: String = "Env Inspector"
    override val version: String = "0.1.0"
    override val description: String =
        "Searchable panel listing every BOSS environment variable, system property and curated runtime setting - secrets are masked by default"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/choksi2212/boss-plugin-env-inspector"

    private val collector: EnvCollector = EnvCollector()
    private var snapshotStore: SnapshotStore? = null
    private var clipboardProvider: ClipboardProvider? = null

    override fun register(context: PluginContext) {
        clipboardProvider = context.clipboardProvider

        val storage = context.pluginStorageFactory?.createStorage(pluginId)
        if (storage != null) {
            val store = SnapshotStore(storage)
            snapshotStore = store
            // Best-effort load on the plugin scope: a corrupt file is
            // swallowed inside SnapshotStore.load() and the user starts
            // with an empty list rather than a crash on first launch.
            CoroutineScope(context.pluginScope.coroutineContext).launch {
                runCatching { store.load() }
            }
        }

        context.panelRegistry.registerPanel(EnvInspectorInfo) { ctx, panelInfo ->
            EnvInspectorComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                collector = collector,
                snapshotStoreSupplier = { snapshotStore },
                clipboardProviderSupplier = { clipboardProvider },
                pluginScope = context.pluginScope,
            )
        }

        // MCP tools: list/get/diff are read-only; reveal is marked
        // readOnly=false so the host's approval surface treats it as a
        // deliberate agent action and logs it in the MCP ledger.
        context.registerMcpToolProvider(
            EnvInspectorMcpToolProvider(
                providerId = pluginId,
                collector = collector,
                snapshotStoreSupplier = { snapshotStore },
            ),
        )
    }

    override fun dispose() {
        clipboardProvider = null
        snapshotStore = null
    }
}
