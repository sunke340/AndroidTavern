package app.tavern

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 一本全局世界书的条目列表（设置 → 世界书 → 点书名）。
 *
 * 做成**真页面**而不是就地换内容：这样它走导航的同一套转场（标准/淡入/关闭）、
 * 有自己的返回栈与滚动位置，而且不用在 `verticalScroll` 里塞 `AnimatedContent`
 * （那里会量到无界高度）。
 *
 * 行是通用行系统（`GroupPiece` + `ListRow`），和其它列表同一套边距与按下反馈；
 * 书可能有几百条，所以是懒加载 —— `ListGroup` 只能用于非懒加载，这里按
 * `rowPieceAt(index, count)` 自己取片型（和预设编辑页同一个办法）。
 *
 * 开关只写"应用内覆盖"，不改导入的 `.json` —— 书还要能原样导回 PC 版。
 */
@Composable
fun WorldBookEntriesScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val book = state.worldBookView

    GlassDetailScaffold(
        title = book?.name ?: "世界书",
        subtitle = book?.let { "${it.entries.size} 条" },
        onBack = viewModel::closeWorldBook,
        glass = state.visualEffects.effective() == VisualEffects.Glass,
        actions = {
            if (book != null) {
                WorldBookBulkMenu { mode -> viewModel.setAllWorldBookEntries(book.name, mode) }
            }
        },
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

        val count = book.entries.size
        LazyColumn(
            modifier = Modifier.fillMaxSize().rubberOverscroll(rememberRubberOverscroll()),
            contentPadding = PaddingValues(
                start = Space.l,
                end = Space.l,
                top = padding.calculateTopPadding() + Space.l,
                // 底部把导航栏/键盘也吃进去，最后一行不会钻到导航栏下面
                bottom = padding.calculateBottomPadding() + Space.l,
            ),
        ) {
            // 强制开启只靠红色表示，含义写在这里；没有强制条目时不占位
            if (book.entries.any { it.forcedOn }) {
                item(key = "forced-hint") {
                    WorldBookForcedHint(
                        modifier = Modifier.padding(
                            start = RowToken.padH,
                            top = Space.xs,
                            bottom = Space.xs,
                        ),
                    )
                }
            }
            itemsIndexed(book.entries, key = { _, entry -> entry.uid }) { index, entry ->
                GroupPiece(piece = rowPieceAt(index, count)) {
                    WorldBookEntryRow(
                        title = entry.title,
                        content = entry.preview,
                        checked = entry.enabled,
                        forcedOn = entry.forcedOn,
                        onToggle = { enabled ->
                            viewModel.setWorldBookEntryEnabled(book.name, entry.uid, enabled)
                        },
                    )
                }
            }
        }
    }
}
