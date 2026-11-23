package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 一本全局世界书的条目列表（设置 → 世界书 → 点书名）。
 *
 * 做成**真页面**而不是就地换内容：这样它走导航的同一套转场（标准/淡入/关闭）、
 * 有自己的返回栈与滚动位置，而且不用在 `verticalScroll` 里塞 `AnimatedContent`
 * （那里会量到无界高度）。
 *
 * 开关只写"应用内覆盖"，不改导入的 `.json`——书还要能原样导回 PC 版。
 */
@Composable
fun WorldBookEntriesScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val book = state.worldBookView

    GlassDetailScaffold(
        title = book?.name ?: "世界书",
        subtitle = book?.let { "${it.entries.size} 条" },
        onBack = viewModel::closeWorldBook,
        glass = state.visualEffects == VisualEffects.Glass,
    ) { padding ->
        if (book == null || !book.loaded) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "读取中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@GlassDetailScaffold
        }
        if (book.entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "这本书里没有条目",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@GlassDetailScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Space.l,
                end = Space.l,
                top = padding.calculateTopPadding() + Space.s,
                bottom = Space.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            items(book.entries, key = { it.uid }) { entry ->
                EntryRow(entry = entry) { enabled ->
                    viewModel.setWorldBookEntryEnabled(book.name, entry.uid, enabled)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: ChatViewModel.WorldBookEntryView, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (entry.preview.isNotBlank()) {
                Text(
                    entry.preview,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.disabledInFile) {
                Text(
                    "文件里已关闭",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = entry.enabled, onCheckedChange = onToggle)
    }
}
