package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 角色卡配置的二级页（作用域：这张卡）。
 *
 * 结构照全局设置：一级「配置」只是入口行，控件放这里。
 * 以后接三级继承（会话 > 卡 > 全局）时，这里的"跟随全局"就是继承入口。
 */
@Composable
fun CardConfigPageScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val page = state.cardConfigPage ?: return
    val cardId = state.detailCardId ?: return
    val detail = state.detail

    // 世界书这页和全局世界书条目页**同一套**：页头半透明、列表从它底下滑过。
    // 另外两页（对话样式 / 关于这张卡）内容短，保持"内容在页头下面开始"。
    val scrollUnderHeader = page == CardConfigPage.WorldBook

    GlassDetailScaffold(
        title = page.title,
        // 页头一行是"标题 + N 条"，和全局世界书详情页一致
        subtitle = if (page == CardConfigPage.WorldBook) {
            detail?.bookEntries?.size?.let { "$it 条" }
        } else {
            null
        },
        onBack = viewModel::closeCardConfigPage,
        glass = state.visualEffects.effective() == VisualEffects.Glass,
        contentTopInset = if (scrollUnderHeader) 0.dp else DetailHeaderHeight,
        actions = if (page == CardConfigPage.WorldBook) {
            { WorldBookBulkMenu { mode -> viewModel.setAllCardBookEntries(mode) } }
        } else {
            null
        },
    ) { padding ->
        val overscroll = rememberRubberOverscroll()
        // 从页头底下滑过的那页，让位必须做在 `verticalScroll` **里面**（内容才能滚到页头下）；
        // 其余页做在外面 —— 内容被裁在页头下方，不会有东西从半透明页头那里透出来。
        val outerInsets = if (scrollUnderHeader) Modifier else Modifier.padding(padding)
        val innerInsets = if (scrollUnderHeader) {
            Modifier.padding(
                top = padding.calculateTopPadding() + DetailHeaderHeight,
                bottom = padding.calculateBottomPadding(),
            )
        } else {
            Modifier
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(outerInsets)
                .rubberOverscroll(overscroll)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l)
                .then(innerInsets),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            when (page) {
                CardConfigPage.WorldBook -> CardWorldBookPage(detail, cardId, viewModel)
                CardConfigPage.MessageStyle -> CardMessageStylePage(detail, cardId, viewModel)
                CardConfigPage.Info -> CardInfoPage(detail)
            }
        }
    }
}

@Composable
private fun CardWorldBookPage(detail: CardDetail?, cardId: String, viewModel: ChatViewModel) {
    val entries = detail?.bookEntries.orEmpty()
    val off = detail?.offUids.orEmpty()
    val forced = detail?.forcedUids.orEmpty()

    if (entries.isEmpty()) {
        EmptyHint("这张卡没有内嵌世界书。")
        return
    }

    // 这一页和全局世界书详情页**完全同一套行**（正文两行预览 + 行尾开关），
    // 只是行的标题取卡里给的 comment（全局书那边取关键字）：卡里人写的标题更有用。
    if (forced.isNotEmpty()) {
        WorldBookForcedHint(
            modifier = Modifier.padding(start = RowToken.padH, top = Space.s),
        )
    }
    ListGroup(
        count = entries.size,
        modifier = Modifier.padding(top = if (forced.isEmpty()) Space.l else Space.xs),
    ) { index, _ ->
        val entry = entries[index]
        WorldBookEntryRow(
            title = entry.comment,
            content = entry.content,
            // 生效状态 = 卡里写的 enabled **叠加** 应用内的两个覆盖（强关 / 强制开启）
            checked = when {
                entry.uid in forced -> true
                entry.uid in off -> false
                else -> entry.enabled
            },
            forcedOn = entry.uid in forced,
            onToggle = { enabled ->
                viewModel.setBookEntryEnabled(cardId, entry.uid, enabled)
            },
        )
    }
}

@Composable
private fun CardMessageStylePage(detail: CardDetail?, cardId: String, viewModel: ChatViewModel) {
    SectionLabel("这张卡的对话样式")

    val options = listOf<MessageStyle?>(null, MessageStyle.Bubble, MessageStyle.Card)
    SegmentedRow(
        options = options,
        selected = detail?.messageStyle,
        label = {
            when (it) {
                null -> "跟随全局"
                MessageStyle.Bubble -> "气泡"
                MessageStyle.Card -> "整行卡片"
            }
        },
        onSelect = { viewModel.setCardMessageStyle(cardId, it) },
    )
}

@Composable
private fun CardInfoPage(detail: CardDetail?) {
    SectionLabel("来源")
    Text(
        if (detail?.imported == true) "从手机导入的卡" else "随包带的卡",
        style = MaterialTheme.typography.bodySmall,
    )

    SectionLabel("字段")
    InfoRow("作者", detail?.creator.orEmpty().ifBlank { "（未填）" })
    InfoRow("版本", detail?.characterVersion.orEmpty().ifBlank { "（未填）" })
    InfoRow(
        "规格",
        listOfNotNull(detail?.spec, detail?.specVersion).joinToString(" / ").ifBlank { "（V1 扁平卡）" },
    )
    InfoRow("标签", detail?.tags.orEmpty().joinToString("、").ifBlank { "（无）" })
    InfoRow("描述", "${detail?.description?.length ?: 0} 字")
    InfoRow("人格", fieldState(detail?.personality))
    InfoRow("场景", fieldState(detail?.scenario))
    InfoRow("示例对话", fieldState(detail?.messageExample))
    InfoRow("备选开场白", "${detail?.alternateGreetings?.size ?: 0} 条")
    InfoRow("内嵌世界书", "${detail?.bookEntries?.size ?: 0} 条")
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/** 空字段说明白一点：很多卡把内容全写进描述，这里是空的其实是作者的写法。 */
private fun fieldState(value: String?): String =
    if (value.isNullOrBlank()) "（空）" else "${value.length} 字"
