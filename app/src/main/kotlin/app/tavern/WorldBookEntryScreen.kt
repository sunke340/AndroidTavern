package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 世界书条目的**全文**（用户 2026-11-25 第 8 条）。
 *
 * 全局书与卡内嵌书共用这一页：条目行里只有两行预览，正文经常是好几段，需要整页看。
 * 正文包在 [SelectionContainer] 里，可以长按复制（原版里也是直接从条目里抄词）。
 *
 * 这一页不提供编辑：条目开关仍在外面的列表行上 —— 看全文时不再摆一个开关，
 * 免得"这一页改了没"变成第二个心智模型。
 */
@Composable
fun WorldBookEntryScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val entry = state.worldBookEntry ?: return

    GlassDetailScaffold(
        title = entry.title,
        // 副标题说明这条来自哪本书 / 哪张卡的内嵌书
        subtitle = entry.source,
        onBack = viewModel::closeWorldBookEntry,
        glass = state.visualEffects.effective() == VisualEffects.Glass,
        headerMode = DetailHeaderMode.Translucent,
    ) { padding ->
        val overscroll = rememberRubberOverscroll()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                .rubberOverscroll(overscroll)
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding())
                .padding(horizontal = Space.l, vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            if (entry.keys.isNotEmpty()) {
                Text(
                    entry.keys.joinToString(" / "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                // 生效状态只有两种（文件里关着但本机强制开启的那种，在这里也显示"已开启"）
                if (entry.enabled) "已开启" else "已关闭",
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.height(2.dp))
            SelectionContainer {
                Text(
                    text = entry.content.ifBlank { "（这条没有正文）" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(Space.l))
        }
    }
}
