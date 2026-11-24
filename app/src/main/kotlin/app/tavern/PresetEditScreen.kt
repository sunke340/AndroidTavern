package app.tavern

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** 顺序列表里一行的高度。拖动库自己按测量结果算，不再需要我们按行高推位次。 */
private val OrderRowHeight = 56.dp

/**
 * 编辑预设（二级页）：一行一段提示词，行尾开关控制发不发，**长按整行拖动换位**。
 *
 * **列表就是这一页本身**，不套在任何固定高度的框里：整页只有这一个滚动区，
 * 内容会从玻璃页头下面滚过去。之前那版把 `LazyColumn` 塞进 `heightIn(max = 480.dp)` 的框里，
 * 底下还垫着一个不滚动的 `Column` —— 看起来像开了个"小窗口"，滚动手感也变成两层。
 * 所以现在用 `contentTopInset = 0.dp` + `contentPadding` 让位（聊天页也是这个套路）。
 *
 * 拖动用 [ReorderableItem]（`sh.calvin.reorderable`）：位次、边缘自动滚、换位动画都是它做。
 * 两个约束：
 * 1. `onMove` 里**同步**改完列表（我们的 `movePrompt` 直接改 `MutableStateFlow`），
 *    否则被拎起来的那行会闪一帧；
 * 2. **松手才落盘**（`commitPromptOrder`）—— 拖动中每次换位都写 SharedPreferences 太浪费。
 */
@Composable
fun PresetEditScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val name = state.presetEditName ?: return
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        viewModel.movePrompt(from.index, to.index)
    }
    // 恢复默认放在右上角：这一页的主体是列表，底部再挂一个按钮会把"列表就是页面"又切一刀
    var menuOpen by remember { mutableStateOf(false) }

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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Space.l,
                end = Space.l,
                top = padding.calculateTopPadding() + DetailHeaderHeight + Space.s,
                bottom = padding.calculateBottomPadding() + Space.xl,
            ),
        ) {
            itemsIndexed(state.promptOrder, key = { _, entry -> entry.identifier }) { index, entry ->
                ReorderableItem(reorderState, key = entry.identifier) { isDragging ->
                    GroupPiece(
                        piece = rowPieceAt(index, state.promptOrder.size),
                        modifier = Modifier.zIndex(if (isDragging) 1f else 0f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(OrderRowHeight)
                                // 拖动中铺一层不透明底，否则被拎起来的那行会透出下面的分隔线
                                .background(
                                    if (isDragging) {
                                        MaterialTheme.colorScheme.surface
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .longPressDraggableHandle(onDragStopped = viewModel::commitPromptOrder)
                                .padding(horizontal = RowToken.padH),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
