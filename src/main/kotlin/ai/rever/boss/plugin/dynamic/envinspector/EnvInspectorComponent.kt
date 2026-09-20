package ai.rever.boss.plugin.dynamic.envinspector

import ai.rever.boss.plugin.api.ClipboardProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope

/**
 * Side panel component. Owns a single [EnvInspectorViewModel] per component
 * instance so each placement has its own filter / search / selected snapshot
 * state without leaking into other windows or panels.
 */
class EnvInspectorComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    collector: EnvCollector,
    snapshotStoreSupplier: () -> SnapshotStore?,
    clipboardProviderSupplier: () -> ClipboardProvider?,
    pluginScope: CoroutineScope,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel: EnvInspectorViewModel = EnvInspectorViewModel(
        collector = collector,
        snapshotStoreSupplier = snapshotStoreSupplier,
        clipboardProviderSupplier = clipboardProviderSupplier,
        pluginScope = pluginScope,
    )

    @Composable
    override fun Content() {
        EnvInspectorContent(viewModel = viewModel)
    }
}
