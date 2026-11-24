package app.tavern

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.tavern.core.preset.PromptOrderEntry
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** 顺序列表里一行的高度。拖动库自己按测量结果算，不再需要我们按行高推位次。 */
private val OrderRowHeight = 56.dp

/**
 * 提示词顺序（预设的 `prompt_order`）：行尾开关控制发不发，**长按整行拖动换位**。
 *
 * 拖动用 [ReorderableItem]（`sh.calvin.reorderable`）而不是手写：手写那版要自己算位次、
 * 自己贴边滚动、自己处理换位后的位移补偿 —— 三件都容易在真机上出问题。库这几件都做了，
 * 还顺带给了 `animateItem` 的位移动画。
 *
 * 两个必须遵守的点（库的 FAQ 里写的）：
 * 1. `onMove` 里**同步改完列表**（我们的 `viewModelScope` 那条不在这里用）；
 * 2. 松手才落盘（[onCommit]）—— 拖动过程中每次换位都写 SharedPreferences 太浪费。
 */
@Composable
fun PresetOrderEditor(
    order: List<PromptOrderEntry>,
    onToggle: (String, Boolean) -> Unit,
    onMove: (Int, Int) -> Unit,
    onCommit: () -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // 这里必须**同步**改完（库要求列表在 onMove 返回前已是新顺序），否则拖动中的那块会闪
        onMove(from.index, to.index)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
    ) {
        itemsIndexed(order, key = { _, entry -> entry.identifier }) { index, entry ->
            ReorderableItem(reorderState, key = entry.identifier) { isDragging ->
                GroupPiece(
                    piece = rowPieceAt(index, order.size),
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
                            .longPressDraggableHandle(onDragStopped = onCommit)
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
                            onCheckedChange = { onToggle(entry.identifier, it) },
                        )
                    }
                }
            }
        }
    }
}

/** 编辑预设（二级页）：标题是预设名，内容就是顺序表 + 恢复原顺序。 */
@Composable
fun PresetEditScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val name = state.presetEditName ?: return
    GlassDetailScaffold(
        title = name,
        subtitle = "${state.promptOrder.size} 段提示词",
        onBack = viewModel::closePresetEditor,
        glass = state.visualEffects.effective() == VisualEffects.Glass,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Space.l),
        ) {
            Spacer(Modifier.height(Space.s))
            PresetOrderEditor(
                order = state.promptOrder,
                onToggle = viewModel::setPromptEnabled,
                onMove = viewModel::movePrompt,
                onCommit = viewModel::commitPromptOrder,
            )
            Spacer(Modifier.height(Space.m))
            SecondaryAction(text = "恢复预设原顺序", onClick = viewModel::resetPromptOrder)
            Spacer(Modifier.height(Space.xl))
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
