package app.tavern

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    // 拖动中“冻住”的片型：行在列表里换位置时形状不能跟着变（那看着就是跳变）
    var frozenKey by remember { mutableStateOf<String?>(null) }
    var frozenPiece by remember { mutableStateOf(RowPiece.Single) }
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
            itemsIndexed(state.promptOrder, key = { _, entry -> entry.identifier }) { index, entry ->
                ReorderableItem(reorderState, key = entry.identifier) { itemScope ->
                    // 长压拖拽的 modifier 只在这个 scope 里可见
                    val handle = with(itemScope) {
                        Modifier.longPressDraggableHandle(
                            onDragStarted = {
                                frozenPiece = rowPieceAt(index, state.promptOrder.size)
                                frozenKey = entry.identifier
                            },
                            onDragStopped = {
                                frozenKey = null
                                viewModel.commitPromptOrder()
                            },
                        )
                    }
                    val isDragging = frozenKey == entry.identifier
                    // 起拖那一刻的片型要冻住：拖动中行会在列表里换位置，而片型是按位置算的
                    // （首尾圆角、中间直角）。不冻的话，被拖着的那行会一边移动一边改形状 —— 就是"跳变"。
                    val piece = if (isDragging) frozenPiece else rowPieceAt(index, state.promptOrder.size)
                    val shape = pieceShape(piece)
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

                    // 行的外观和别的列表完全一致（GroupPiece：组的外轮廓只画一圈、衔接处只有一条分隔线），
                    // 拖动只改投影与缩放 —— 形状不动，所以不会跳变，也不会"像是被裁出来的一条"。
                    GroupPiece(
                        piece = piece,
                        modifier = Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.shape = shape
                                shadowElevation = lift.toPx()
                            },
                        dividerInset = RowToken.padH + 16.dp + Space.m,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(RowToken.height)
                                .then(handle)
                                .padding(horizontal = RowToken.padH),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 抓握纹在最左边：先"抓住"，再读名字
                            DragGrip(highlighted = isDragging)
                            Spacer(Modifier.width(Space.m))
                            Text(
                                text = promptLabel(entry.identifier),
                                style = RowTitleStyle,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = entry.enabled,
                                onCheckedChange = { viewModel.setPromptEnabled(entry.identifier, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 片型对应的形状：和 `GroupPiece` 内部一致（首/尾两片各圆两个角，中间是直角）。 */
private fun pieceShape(piece: RowPiece) = when (piece) {
    RowPiece.Single -> RoundedCornerShape(Radius.group)
    RowPiece.Top -> RoundedCornerShape(topStart = Radius.group, topEnd = Radius.group)
    RowPiece.Bottom -> RoundedCornerShape(bottomStart = Radius.group, bottomEnd = Radius.group)
    RowPiece.Middle -> RectangleShape
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
