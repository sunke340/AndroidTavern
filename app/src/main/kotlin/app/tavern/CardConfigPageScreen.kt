package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

    // 三页都和全局世界书条目页同一套：页头半透明、内容从它底下滑过（用户 2026-11-25 第 7 条：
    // 二级页的页头不该是一条纯色）。
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
        contentTopInset = 0.dp,
        actions = if (page == CardConfigPage.WorldBook) {
            { WorldBookBulkMenu { mode -> viewModel.setAllCardBookEntries(mode) } }
        } else {
            null
        },
    ) { padding ->
        // **按页分发**：三页共用这一个骨架，内容却完全不同。
        // 6f4dd53 把这里写成"只要 scrollUnderHeader 就渲染世界书页"，于是「对话样式」和
        // 「关于这张卡」都被内嵌世界书顶掉了（v149 / v150 的真机包里就是错的）。
        if (page == CardConfigPage.WorldBook) {
            // 条目多的时候**必须懒加载**：非懒加载的 `ListGroup` 会在进页的第一帧里把全部条目
            // 组合出来 —— 真机日志里出现过 448ms 一帧，转场动画直接被吃掉（用户 2026-11-24）。
            CardWorldBookPage(
                detail = detail,
                cardId = cardId,
                viewModel = viewModel,
                padding = padding,
            )
        } else {
            val overscroll = rememberRubberOverscroll()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding())
                    .rubberOverscroll(overscroll)
                    .verticalScroll(rememberScrollState())
                    .padding(top = padding.calculateTopPadding() + DetailScrollUnderTop)
                    .padding(horizontal = Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                when (page) {
                    CardConfigPage.MessageStyle -> CardMessageStylePage(detail, cardId, viewModel)
                    CardConfigPage.Info -> CardInfoPage(detail)
                    else -> Unit
                }
            }
        }
    }
}

/**
 * 卡内嵌书的条目页。
 *
 * 用 `LazyColumn` 而不是 `ListGroup`：一本卡的内嵌书可能几百条，`ListGroup` 是第一帧
 * 就把所有行都组合出来的（实测一帧 257–448ms → 进页动画没了）。
 * 让位走 `contentPadding`，内容从半透明页头底下滑过 —— 和全局世界书条目页同一套。
 */
@Composable
private fun CardWorldBookPage(
    detail: CardDetail?,
    cardId: String,
    viewModel: ChatViewModel,
    padding: PaddingValues,
) {
    val entries = detail?.bookEntries.orEmpty()
    val off = detail?.offUids.orEmpty()
    val forced = detail?.forcedUids.orEmpty()
    val contentPadding = PaddingValues(
        start = Space.l,
        end = Space.l,
        top = padding.calculateTopPadding() + DetailHeaderHeight + Space.l,
        bottom = padding.calculateBottomPadding() + Space.l,
    )

    if (entries.isEmpty()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().rubberOverscroll(rememberRubberOverscroll()),
            contentPadding = contentPadding,
        ) {
            item { EmptyHint("这张卡没有内嵌世界书。") }
        }
        return
    }

    val count = entries.size
    LazyColumn(
        modifier = Modifier.fillMaxSize().rubberOverscroll(rememberRubberOverscroll()),
        contentPadding = contentPadding,
    ) {
        if (forced.isNotEmpty()) {
            item {
                WorldBookForcedHint(
                    modifier = Modifier.padding(
                        start = RowToken.padH,
                        top = Space.xs,
                        bottom = Space.xs,
                    ),
                )
            }
        }
        itemsIndexed(entries) { index, entry ->
            GroupPiece(piece = rowPieceAt(index, count)) {
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
