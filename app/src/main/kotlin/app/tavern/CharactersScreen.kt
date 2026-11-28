package app.tavern

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tavern.data.cards.CardRef

/**
 * 角色卡网格：两列，每格是一张满幅卡图（3:4），名字与元信息压在图底部的一条磨砂条上。
 *
 * 为什么不再写角色描述：多数卡这里写的是人设正文，截两行读不出意思，还把卡片撑高
 * （有描述的卡比没描述的高一截）。副信息换成**会话数 / 内嵌书**——它回答的是
 * "我聊过这张吗"，而这些数字本来就已经在内存里（会话列表 + 卡的 bookEntryCount）。
 *
 * 磨砂条为什么不用 `GlassSurface`：角色卡在内容录图层里，玻璃采的是那一层，
 * 会采到自己这一帧（页头胶囊"无限套娃"就是这么来的）。这里用预先解出的 ~32px 小图
 * 放大画回去 —— 真糊、便宜（5KB/张）、不碰图层时序。详见 [CardFrost]。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CharactersScreen(
    state: UiState,
    viewModel: ChatViewModel,
    // 滚动位置由上层持有：从角色详情返回时网格不该回到顶部。
    gridState: LazyGridState = rememberLazyGridState(),
    // 悬浮底栏压在内容上，网格底部要让出它的高度。
    bottomPadding: Dp = 0.dp,
) {
    // 页头一直铺到状态栏后面，网格要让出"状态栏 + 页头"的高度
    val statusBar = statusBarTop()
    val overscroll = rememberRubberOverscroll()

    // 长按出的抽屉与删除确认：与会话、世界书、模型档案同一个手势
    var pendingDelete by remember { mutableStateOf<CardRef?>(null) }
    val sheet = LocalSheetController.current
    // 每张卡有几个会话：直接数已经加载的会话列表，不再多查一次库
    val sessionCounts = remember(state.sessions) {
        state.sessions.groupingBy { it.cardId }.eachCount()
    }

    // 搜索 + 标签筛选（P0-8）。标签来自卡列表缓存（`CardStore` 在第一次解析时存下来的），
    // 所以这里不再解 PNG。
    var query by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    val allTags = remember(state.cards) {
        state.cards.flatMap { it.tags }.map { it.trim() }.filter { it.isNotEmpty() }
            .distinct().sorted().take(24)
    }
    val filtered = remember(state.cards, query, selectedTag) {
        val text = query.trim()
        state.cards.filter { card ->
            val tagOk = selectedTag == null || selectedTag in card.tags
            val textOk = text.isEmpty() ||
                card.name.contains(text, ignoreCase = true) ||
                card.description.contains(text, ignoreCase = true) ||
                card.tags.any { it.contains(text, ignoreCase = true) }
            tagOk && textOk
        }
    }

    if (!state.cardsLoaded) {
        // 还没读完库：别把"未加载"说成"还没有卡"
        return
    }
    if (state.cards.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Space.l)
                .padding(top = PageHeaderHeight + Space.l + statusBar),
        ) {
            EmptyHint("还没有角色卡。点右上「＋」导入一张 ST 的 PNG 卡。")
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().rubberOverscroll(overscroll),
        state = gridState,
        contentPadding = PaddingValues(
            start = Space.l,
            end = Space.l,
            top = PageHeaderHeight + Space.s + statusBar,
            bottom = Space.l + bottomPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(Space.m - 2.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m - 2.dp),
    ) {
        // 搜索框与标签行各占满一整行（两列网格里要 span 整行）
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                CardSearchField(query = query, onQueryChange = { query = it })
                if (allTags.isNotEmpty()) {
                    TagFilterRow(
                        tags = allTags,
                        selected = selectedTag,
                        onSelect = { selectedTag = if (selectedTag == it) null else it },
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val hint = query.trim().ifBlank { selectedTag.orEmpty() }
                EmptyHint(if (hint.isBlank()) "没有匹配的卡。" else "没有匹配「$hint」的卡。")
            }
        }

        items(filtered, key = { it.id }) { card ->
            val press = remember { MutableInteractionSource() }
            val tint = rememberPressTint(press)
            val count = sessionCounts[card.id] ?: 0

            Box(
                modifier = Modifier
                    // 只做"位置变化"动画，**不要出现/消失的淡入淡出**（2026-11-23 用户报：
                    // 从二级页返回时卡片"颜色由浅到深"再显出来）。换页时这棵子树是重建的，
                    // 新的 LazyGrid 会被测两遍，第二遍里所有 item 对动画器都是"第一次见"
                    // → fadeInSpec 把它们整体从 alpha 0 淡上来。传 null 就只留 placement。
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = tween(Motion.Slow, easing = Motion.Standard),
                        fadeOutSpec = null,
                    )
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.group))
                    .combinedClickable(
                        interactionSource = press,
                        indication = null,
                        onClick = { viewModel.openDetail(card.id) },
                        onLongClick = {
                            sheet.show(
                                title = card.name,
                                actions = listOf(
                                    SheetAction("开始新会话") {
                                        viewModel.startSessionForCard(card.id)
                                    },
                                    SheetAction("查看详情") { viewModel.openDetail(card.id) },
                                    SheetAction("删除角色卡", destructive = true) {
                                        pendingDelete = card
                                    },
                                ),
                            )
                        },
                    ),
            ) {
                CardAvatar(
                    cardId = card.id,
                    fallbackText = card.name,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                )
                CardBand(
                    cardId = card.id,
                    name = card.name,
                    meta = buildString {
                        append(if (count == 0) "未开始" else "$count 个会话")
                        if (card.bookEntryCount > 0) append(" · 内嵌书 ${card.bookEntryCount} 条")
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                // 按压：图上压 8% 黑（白面板才用主色底，图上盖紫会染色）
                if (tint > 0f) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.08f * tint)),
                    )
                }
            }
        }
    }

    pendingDelete?.let { card ->
        val count = sessionCounts[card.id] ?: 0
        ConfirmDeleteDialog(
            title = "删除角色卡？",
            message = "「${card.name}」和它的 $count 个会话会一起删掉，卡文件也会从应用里删除，不能撤销。",
            onConfirm = {
                pendingDelete = null
                viewModel.deleteCard(card.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** 卡图底部那条磨砂条的高度：名字一行 + 元信息一行 + 上下留白。 */
private val CardBandHeight: Dp = 56.dp

/**
 * 卡图底部的磨砂条：糊图 + 跟主题的半透明面 + 一条上沿亮线 + 名字与元信息。
 *
 * 层次是固定的：糊图 → 面 → 亮线 → 文字。亮线是这个材质"玻璃边缘"的唯一暗示，
 * 别加投影（卡片本身不浮起，见 C 方向"线感不浮起"）。
 */
@Composable
private fun CardBand(
    cardId: String,
    name: String,
    meta: String,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier = modifier.fillMaxWidth().height(CardBandHeight)) {
        CardFrost(cardId = cardId, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(surface.copy(alpha = 0.72f)))
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.28f)),
        )
        Column(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            Text(
                text = name,
                style = RowTitleStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = RowMetaStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 角色卡搜索框：一颗圆角输入条，空的时候显示占位文案，非空时右边一个「×」清空。
 *
 * 为什么只搜名字与简介：标签在卡 PNG 里，得解文件才知道；几十张卡每次输入都解一遍，
 * 卡列表首帧就废了（卡面缓存只缓存图片，不缓存字段）。
 */
@Composable
private fun CardSearchField(query: String, onQueryChange: (String) -> Unit) {
    val palette = LocalTavernPalette.current
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Space.s)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, palette.cardBorder, shape)
            .padding(horizontal = Space.l, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        "搜索名字 / 简介",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            },
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "清空搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onQueryChange("") },
            )
        }
    }
}

/** 标签筛选：一排可横向滑动的胶囊，点一下选中、再点取消。 */
@Composable
private fun TagFilterRow(tags: List<String>, selected: String?, onSelect: (String) -> Unit) {
    val palette = LocalTavernPalette.current
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        contentPadding = PaddingValues(end = Space.s),
    ) {
        items(tags) { tag ->
            val on = tag == selected
            val shape = RoundedCornerShape(Radius.pill)
            Text(
                text = tag,
                style = MaterialTheme.typography.labelMedium,
                color = if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(shape)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surface,
                    )
                    .border(1.dp, if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else palette.cardBorder, shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(tag) }
                    .padding(horizontal = Space.m, vertical = 6.dp),
            )
        }
    }
}
