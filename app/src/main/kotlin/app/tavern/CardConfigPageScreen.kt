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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun CardConfigPageScreen(
    state: UiState,
    viewModel: ChatViewModel,
    /** 条目列表的滚动位置由上层持有：点进「条目全文」再返回时不能回到顶部（M16）。 */
    bookListState: LazyListState = rememberLazyListState(),
) {
    val page = state.cardConfigPage ?: return
    val cardId = state.detailCardId ?: return
    val detail = state.detail

    // 三页都和全局世界书条目页同一套：页头半透明、内容从它底下滑过（用户 2026-11-25 第 7 条：
    // 二级页的页头不该是一条纯色）。
    //
    // **按页分发，骨架也分开调**：内嵌书是列表（首项 +16dp），另两页是 Column（贴页头底边）。
    // `6f4dd53` 曾把分发写成 `if (scrollUnderHeader)` —— 常量一固定，另两页就静默渲染成世界书
    // 列表（见 device-verification.md M10）；首项留白也随之只能各写一份，所以这里不共用一次调用。
    when (page) {
        CardConfigPage.WorldBook -> GlassDetailScaffold(
            title = page.title,
            // 页头一行是"标题 + N 条"，和全局世界书详情页一致
            subtitle = detail?.bookEntries?.size?.let { "$it 条" },
            onBack = viewModel::closeCardConfigPage,
            glass = state.visualEffects.effective() == VisualEffects.Glass,
            headerMode = DetailHeaderMode.Translucent,
            contentSpacing = Space.l,
            actions = { WorldBookBulkMenu { mode -> viewModel.setAllCardBookEntries(mode) } },
        ) { padding ->
            // 条目多的时候**必须懒加载**：非懒加载的 `ListGroup` 会在进页的第一帧里把全部条目
            // 组合出来 —— 真机日志里出现过 448ms 一帧，转场动画直接被吃掉（用户 2026-11-24）。
            CardWorldBookPage(
                detail = detail,
                cardId = cardId,
                viewModel = viewModel,
                padding = padding,
                listState = bookListState,
            )
        }

        CardConfigPage.MessageStyle, CardConfigPage.Info -> GlassDetailScaffold(
            title = page.title,
            onBack = viewModel::closeCardConfigPage,
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
                    .padding(horizontal = Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                when (page) {
                    CardConfigPage.MessageStyle -> CardMessageStylePage(detail, cardId, viewModel, state)
                    else -> CardInfoPage(detail)
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
    listState: LazyListState,
) {
    val entries = detail?.bookEntries.orEmpty()
    val off = detail?.offUids.orEmpty()
    val forced = detail?.forcedUids.orEmpty()
    val contentPadding = PaddingValues(
        start = Space.l,
        end = Space.l,
        top = padding.calculateTopPadding(),
        bottom = padding.calculateBottomPadding() + Space.l,
    )

    // 搜索（用户 2026-11-25 第 16 条）：卡内嵌书的正文本来就在内存里，纯 UI 侧过滤就够。
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, entries) {
        if (query.isBlank()) {
            entries
        } else {
            val q = query.trim().lowercase()
            entries.filter { entry ->
                entry.comment.lowercase().contains(q) ||
                    entry.keys.any { it.lowercase().contains(q) } ||
                    entry.content.lowercase().contains(q)
            }
        }
    }

    val count = filtered.size
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().rubberOverscroll(rememberRubberOverscroll()),
        contentPadding = contentPadding,
    ) {
        item(key = "search") {
            SearchField(
                value = query,
                placeholder = "搜索标题 / 关键词 / 正文",
                onValueChange = { query = it },
                modifier = Modifier.padding(bottom = Space.s),
            )
        }
        if (filtered.isEmpty()) {
            item(key = "empty") {
                EmptyHint(
                    if (entries.isEmpty()) {
                        "这张卡没有内嵌世界书。"
                    } else {
                        "没有匹配的条目"
                    },
                )
            }
        } else {
            if (filtered.any { it.uid in forced }) {
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
            itemsIndexed(filtered, key = { _, entry -> entry.uid }) { index, entry ->
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
                        // 点行看全文（用户 2026-11-25 第 8 条）
                        onClick = { viewModel.openCardBookEntry(entry.uid) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CardMessageStylePage(
    detail: CardDetail?,
    cardId: String,
    viewModel: ChatViewModel,
    state: UiState,
) {
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

    // 这张卡的气泡字号（用户 2026-11-26 第 6 条）：null = 跟随全局。
    SectionLabel("气泡字号")
    SegmentedRow(
        options = listOf<Float?>(null) + BubbleFontSizes,
        selected = state.cardOverride.bubbleFontSize,
        label = { it?.let(::bubbleFontSizeLabel) ?: "跟随全局" },
        onSelect = { viewModel.useCardBubbleFontSize(it) },
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
