package app.tavern

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** 一行的高度。拖动库自己按测量结果定位，不再需要我们按行高推位次。 */
private val PromptRowHeight = 60.dp

/**
 * 编辑预设（二级页）：一行一段提示词，行尾开关控制发不发，**长按整行拖动换位**。
 *
 * ## 这里**不用**分组面板（`GroupPiece`）
 *
 * 会话 / 设置那种列表是"同一个实体的多行"，分组面板对：一整个圆角容器、内部用分隔线，
 * 首尾两片带圆角、中间两片是直角。但**可拖动排序的列表是"一叠独立对象"**：
 * 一拎起来，那片的形状就会从"直角"变成"带头尾圆角"，看着像被人从整块里裁了一条出来；
 * 而其余行又原地不动，更像裁剪而不是"挪走一个东西"。所以这一页改成**每行一张独立卡片**：
 *
 * - 每行自己就是完整的圆角矩形（`Radius.card`）+ 1dp 描边，**行与行之间不留缝**；
 * - 拖动时那张卡**抬起来**：投影 0 → 10dp、缩放 1.00 → 1.03（都用 `Motion.Fast`），
 *   画在同层最上面（`zIndex`），于是它在视觉上离开纸面、盖住邻居；
 * - 松手落回：投影与缩放同时收回去，卡片在原位。
 *
 * 滚动照旧挂橡皮筋（`rubberOverscroll`）：这一页和其它列表页一样，要能"拉过头再弹回来"。
 *
 * 形状、圆角、间距在拖动前后**完全一样**，所以不会有"跳变"。
 *
 * ## 其它约束
 *
 * - `onMove` 里**同步**改完列表（`movePrompt` 直接改 `MutableStateFlow`），否则被拎起来那行会闪；
 * - **松手才落盘**（`commitPromptOrder`），拖动中每次换位都写 SharedPreferences 太浪费；
 * - 恢复默认在页头右上角的圆钮菜单里（列表既然是整页，底部不再挂按钮）。
 */
@Composable
fun PresetEditScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val name = state.presetEditName ?: return
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        viewModel.movePrompt(from.index, to.index)
    }
    var menuOpen by remember { mutableStateOf(false) }
    // 这一页也是可滚动面：跟其它列表一样要能"拉过头再弹回来"
    val overscroll = rememberRubberOverscroll()

    GlassDetailScaffold(
        title = name,
        subtitle = "${state.promptOrder.size} 段提示词",
        onBack = viewModel::closePresetEditor,
        glass = state.visualEffects.effective() == VisualEffects.Glass,
        // 自己用 contentPadding 让位：列表满屏、能从页头底下穿过去
        contentTopInset = 0.dp,
        actions = {
            CapsuleIcon(
                icon = Icons.Filled.MoreVert,
                contentDescription = "更多",
            ) { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("恢复默认顺序") },
                    onClick = {
                        menuOpen = false
                        viewModel.resetPromptOrder()
                    },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().rubberOverscroll(overscroll),
            contentPadding = PaddingValues(
                start = Space.l,
                end = Space.l,
                top = padding.calculateTopPadding() + DetailHeaderHeight + Space.l,
                bottom = padding.calculateBottomPadding() + Space.xl,
            ),
        ) {
            itemsIndexed(state.promptOrder, key = { _, entry -> entry.identifier }) { _, entry ->
                ReorderableItem(reorderState, key = entry.identifier) { isDragging ->
                    val lift by animateDpAsState(
                        targetValue = if (isDragging) 10.dp else 0.dp,
                        animationSpec = tween(Motion.Fast),
                        label = "prompt-lift",
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (isDragging) 1.03f else 1f,
                        animationSpec = tween(Motion.Fast),
                        label = "prompt-scale",
                    )
                    PromptOrderCard(
                        label = promptLabel(entry.identifier),
                        enabled = entry.enabled,
                        dragging = isDragging,
                        lift = lift,
                        scale = scale,
                        onToggle = { viewModel.setPromptEnabled(entry.identifier, it) },
                        modifier = Modifier.zIndex(if (isDragging) 1f else 0f),
                        onCommit = viewModel::commitPromptOrder,
                        // 长压拖拽的 modifier 只在这个 scope 里可见，得把它一起传下去
                        scope = this,
                    )
                }
            }
        }
    }
}

/**
 * 一张提示词卡：名字 + 开关 + 右侧抓握纹。
 *
 * 长压整张卡就能拖（见 [PresetEditScreen] 的注释）。右下角那三道横线只是**暗示**：
 * 它不可点、不接管手势，纯视觉 —— 没有它的话"这行能拖"完全没有线索。
 */
@Composable
private fun PromptOrderCard(
    label: String,
    enabled: Boolean,
    dragging: Boolean,
    lift: Dp,
    scale: Float,
    onToggle: (Boolean) -> Unit,
    onCommit: () -> Unit,
    scope: ReorderableCollectionItemScope,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Radius.card)
    val borderColor = LocalTavernPalette.current.cardBorder
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PromptRowHeight)
            // 投影与缩放都走 graphicsLayer：拖动时这张卡要"浮"在同层之上
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.shape = shape
                shadowElevation = lift.toPx()
            }
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, borderColor, shape)
            .then(with(scope) { Modifier.longPressDraggableHandle(onDragStopped = onCommit) })
            .padding(start = Space.s, end = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 抓握纹在最左边：先"抓住"，再读名字
        DragGrip(highlighted = dragging)
        Spacer(Modifier.width(Space.s))
        Text(
            text = label,
            style = RowTitleStyle,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** 抓握纹：三道横线。只是"这里能拖"的暗示，不接管任何手势。 */
@Composable
private fun DragGrip(highlighted: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = if (highlighted) 0.9f else 0.45f,
    )
    Canvas(modifier = Modifier.size(width = 16.dp, height = 18.dp)) {
        val stroke = 1.5.dp.toPx()
        val gap = size.height / 3f
        for (i in 0..2) {
            val y = gap * i + gap / 2f
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** identifier → 人话。原版界面里的叫法（Main Prompt / Post-History Instructions …）。 */
private fun promptLabel(identifier: String): String = when (identifier) {
    "main" -> "主提示词"
    "nsfw" -> "辅助提示词"
    "enhanceDefinitions" -> "补强设定"
    "worldInfoBefore" -> "世界书（前）"
    "worldInfoAfter" -> "世界书（后）"
    "charDescription" -> "角色描述"
    "charPersonality" -> "角色性格"
    "scenario" -> "场景"
    "dialogueExamples" -> "示例对话"
    "chatHistory" -> "聊天历史"
    "jailbreak" -> "历史后指令"
    "personaDescription" -> "用户人设"
    else -> identifier
}
