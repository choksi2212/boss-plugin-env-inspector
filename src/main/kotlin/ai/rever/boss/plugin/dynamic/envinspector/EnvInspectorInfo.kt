package ai.rever.boss.plugin.dynamic.envinspector

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.Terminal

/**
 * Panel descriptor for the Env Inspector side panel.
 *
 * The slot is `left.bottom` and the default order is 78 so it sorts below
 * the file-tree / search panels but above the log / debug tools in the
 * bottom of the left rail. The id string is `env-inspector`; collisions with
 * other plugins' panel ids are guarded by the host's id-prefix check.
 */
object EnvInspectorInfo : PanelInfo {
    override val id: PanelId = PanelId("env-inspector", 78)
    override val displayName: String = "Env Inspector"
    override val icon = FeatherIcons.Terminal
    override val defaultSlotPosition: Panel = left.bottom
}
