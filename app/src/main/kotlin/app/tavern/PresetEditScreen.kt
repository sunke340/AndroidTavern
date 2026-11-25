package app.tavern

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 编辑预设（二级页）：一行一段提示词，行尾开关控制发不发，**长按整行拖动换位**。
 *
 * ## 每一行都是四角全圆的一颗胶囊
 *
 * 之前试过两种：分组面板（首尾圆角、中间直角）和"每行各画一圈描边的卡片"。前者在拖动时会改形状
 * （片型按位置算），后者两条描边并排就是 2px 双线。现在固定成**同一种形状**：
 * 每行都是 `Radius.group` 四角全圆、行与行贴合、**行本身不画描边**，衔接处只画一条 1px 分隔线，
 * 并且这条线的两端各让出 `Radius.group`（避开两端的圆弧）—— 于是既没有双线，也没有"落下时形状变一下"。
 *
 * 拖动只改**投影**（0→10dp）与**缩放**（1→1.03，`Motion.Fast`）+ `zIndex` 置顶：形状全程不变。
 * 拖动中藏起这一行下方的分隔线，免得那条线跟着胶囊一起被拎走。
 *
 * ## 其它约束
 *
 * - `onMove` 里**同步**改完列表（`movePrompt` 直接改 `MutableStateFlow`），否则被拎起来那行会闪；
 * - **松手才落盘**（`commitPromptOrder`），拖动中每次换位都写 SharedPreferences 太浪费；
 * - 恢复默认在页头右上角的圆钮菜单里；列表就是这一页本身（`headerMode` + `contentPadding`）。
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
    val pill = RoundedCornerShape(Radius.group)
    val dividerColor = LocalTavernPalette.current.divider

    GlassDetailScaffold(
        title = name,
        subtitle = "${state.promptOrder.size} 段提示词",
        onBack = viewModel::closePresetEditor,
        glass = state.visualEffects.effective() == VisualEffects.Glass,
        // 自己用 contentPadding 让位：列表满屏、能从页头底下穿过去；首项离页头底边 16dp
        headerMode = DetailHeaderMode.Translucent,
        contentSpacing = Space.l,
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
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + Space.xl,
            ),
        ) {
            itemsIndexed(state.promptOrder, key = { _, entry -> entry.identifier }) { index, entry ->
                ReorderableItem(reorderState, key = entry.identifier) { isDragging ->
                    // 长压拖拽的 modifier 只在这个 scope 里可见
                    val handle = with(this) {
                        Modifier.longPressDraggableHandle(
                            onDragStopped = { viewModel.commitPromptOrder() },
                        )
                    }
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

                    Column(Modifier.fillMaxWidth().zIndex(if (isDragging) 1f else 0f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(RowToken.height)
                                // 投影与缩放都走 graphicsLayer：形状固定是这颗胶囊，拖动时不改形状
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    shape = pill
                                    shadowElevation = lift.toPx()
                                }
                                .background(MaterialTheme.colorScheme.surface, pill),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
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

                        // 衔接处只有这一条线：两端各让出圆角半径，免得压在圆弧上
                        if (index < state.promptOrder.lastIndex && !isDragging) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Radius.group)
                                    .height(1.dp)
                                    .background(dividerColor),
                            )
                        }
                    }
                }
            }
        }
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
