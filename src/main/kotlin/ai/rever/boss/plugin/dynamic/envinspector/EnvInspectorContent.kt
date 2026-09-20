package ai.rever.boss.plugin.dynamic.envinspector

import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Compose entry point for the Env Inspector side panel.
 *
 * Layout, top to bottom:
 *
 *  - Search field + category filter row
 *  - Lazy list of entries (each row: key + value, click to copy)
 *  - Snapshot toolbar (save) + snapshot list
 *
 * No log line, no exception, no error message carries a real secret value.
 * The masked token is the only string that ever leaves this composable for
 * a secret-looking key.
 */
@Composable
fun EnvInspectorContent(viewModel: EnvInspectorViewModel) {
    BossTheme {
        EnvInspectorPanel(viewModel)
    }
}

@Composable
private fun EnvInspectorPanel(viewModel: EnvInspectorViewModel) {
    val visibleEntries by viewModel.visibleEntries.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snapshots by viewModel.snapshots.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // --- filter row ---
            EnvInspectorToolbar(
                selectedCategory = selectedCategory,
                searchQuery = searchQuery,
                onCategoryChange = viewModel::setCategory,
                onSearchChange = viewModel::setSearchQuery,
                onRefresh = viewModel::refresh,
            )

            Divider(color = BossThemeColors.BorderColor)

            // --- toast row ---
            if (statusMessage != null || errorMessage != null) {
                ToastRow(
                    statusMessage = statusMessage,
                    errorMessage = errorMessage,
                    onDismiss = viewModel::clearMessages,
                )
            }

            // --- main list ---
            EnvEntryList(
                entries = visibleEntries,
                onCopy = viewModel::copyValue,
                modifier = Modifier.weight(1f),
            )

            Divider(color = BossThemeColors.BorderColor)

            // --- snapshot footer ---
            SnapshotFooter(
                snapshots = snapshots,
                onSave = viewModel::saveSnapshot,
                onDelete = viewModel::deleteSnapshot,
            )
        }
    }
}

@Composable
private fun EnvInspectorToolbar(
    selectedCategory: EnvCategory?,
    searchQuery: String,
    onCategoryChange: (EnvCategory?) -> Unit,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Env",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = BossThemeColors.TextPrimary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            SearchField(
                value = searchQuery,
                onChange = onSearchChange,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    modifier = Modifier.size(14.dp),
                    tint = BossThemeColors.TextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryChip(
                label = "All",
                selected = selectedCategory == null,
                onClick = { onCategoryChange(null) },
            )
            Spacer(modifier = Modifier.width(4.dp))
            EnvCategory.values().forEach { cat ->
                CategoryChip(
                    label = cat.label,
                    selected = selectedCategory == cat,
                    onClick = { onCategoryChange(cat) },
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        cursorBrush = SolidColor(BossThemeColors.AccentColor),
        textStyle = TextStyle(
            color = BossThemeColors.TextPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        ),
        modifier = modifier
            .height(24.dp)
            .background(BossThemeColors.SurfaceColor, RoundedCornerShape(4.dp))
            .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = "Search key or source",
                        fontSize = 11.sp,
                        color = BossThemeColors.TextMuted,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) BossThemeColors.AccentColor.copy(alpha = 0.18f)
        else BossThemeColors.SurfaceColor
    val border = if (selected) BossThemeColors.AccentColor
        else BossThemeColors.BorderColor
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
        )
    }
}

@Composable
private fun ToastRow(
    statusMessage: String?,
    errorMessage: String?,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(statusMessage, errorMessage) {
        delay(2500)
        onDismiss()
    }
    val isError = errorMessage != null
    val text = errorMessage ?: statusMessage ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) BossThemeColors.ErrorColor else BossThemeColors.SuccessColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = BossThemeColors.TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(18.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(12.dp),
                tint = BossThemeColors.TextPrimary,
            )
        }
    }
}

@Composable
private fun EnvEntryList(
    entries: List<EnvEntry>,
    onCopy: (EnvEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    Box(modifier = modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .lazyListScrollbar(
                        listState = listState,
                        direction = Orientation.Vertical,
                        config = getPanelScrollbarConfig(),
                    ),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(entries, key = { "${it.source}|${it.key}" }) { entry ->
                    EnvEntryRow(entry = entry, onCopy = { onCopy(entry) })
                }
            }
        }
    }
}

@Composable
private fun EnvEntryRow(entry: EnvEntry, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.key,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = BossThemeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.value,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (entry.value == EnvCollector.MASKED_VALUE) BossThemeColors.WarningColor
                    else BossThemeColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.source,
                fontSize = 9.sp,
                color = BossThemeColors.TextMuted,
                maxLines = 1,
            )
        }
        IconButton(onClick = onCopy, modifier = Modifier.size(22.dp)) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy value",
                modifier = Modifier.size(12.dp),
                tint = BossThemeColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No matching entries",
            fontSize = 12.sp,
            color = BossThemeColors.TextMuted,
        )
    }
}

@Composable
private fun SnapshotFooter(
    snapshots: List<EnvSnapshot>,
    onSave: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Snapshots",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = BossThemeColors.TextPrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onSave, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "Save snapshot",
                    modifier = Modifier.size(12.dp),
                    tint = BossThemeColors.AccentColor,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Save", fontSize = 11.sp, color = BossThemeColors.AccentColor)
            }
        }
        if (snapshots.isEmpty()) {
            Text(
                text = "No snapshots yet",
                fontSize = 10.sp,
                color = BossThemeColors.TextMuted,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(snapshots, key = { it.id }) { snap ->
                    SnapshotRow(
                        snapshot = snap,
                        onDelete = { onDelete(snap.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapshotRow(snapshot: EnvSnapshot, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.SurfaceColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = snapshot.label,
                fontSize = 10.sp,
                color = BossThemeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${snapshot.entries.size} entries",
                fontSize = 9.sp,
                color = BossThemeColors.TextMuted,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(18.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete snapshot",
                modifier = Modifier.size(10.dp),
                tint = BossThemeColors.ErrorColor,
            )
        }
    }
}

