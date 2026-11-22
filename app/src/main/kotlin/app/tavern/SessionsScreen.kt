package app.tavern

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavern.data.chat.ChatSession

/**
 * 会话列表：最近活动在最上面。
 *
 * 删除走**长按**：行尾不再放删除按钮（那会让每行都很挤）。
 * 长按 → 动作单 → 删除 → 二次确认：动作单以后还能放"重命名 / 导出"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    var actionTarget by remember { mutableStateOf<ChatSession?>(null) }
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }

    PageScaffold(
        title = "会话",
        action = { PrimaryAction("＋ 新建", onClick = viewModel::newSession) },
    ) {
        if (state.sessions.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(Space.l)) {
                EmptyHint("还没有会话。点右上「新建」，选一张卡开始。")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = Space.s, bottom = Space.l),
                verticalArrangement = Arrangement.Top,
            ) {
                items(state.sessions, key = { it.id }) { session ->
                    ListRow(
                        modifier = Modifier.animateItem(
                            fadeInSpec = androidx.compose.animation.core.tween(
                                Motion.Base,
                                easing = Motion.Decelerate,
                            ),
                            placementSpec = androidx.compose.animation.core.tween(
                                Motion.Slow,
                                easing = Motion.Standard,
                            ),
                            fadeOutSpec = androidx.compose.animation.core.tween(Motion.Fast),
                        ),
                        title = session.cardName,
                        subtitle = "${formatSessionTime(session.updatedAt)} · ${session.messageCount} 条",
                        leading = {
                            CardAvatar(
                                cardId = session.cardId,
                                fallbackText = session.cardName,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                        onClick = { viewModel.openSession(session.id) },
                        onLongClick = { actionTarget = session },
                    )
                }
            }
        }
    }

    // 选卡弹层（新建会话前必须先选卡）
    CardPickerSheet(state = state, viewModel = viewModel)

    // 长按动作单：现在只有删除，以后可以加"重命名 / 导出"
    actionTarget?.let { session ->
        ModalBottomSheet(
            onDismissRequest = { actionTarget = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s),
                )
                ListItem(
                    headlineContent = {
                        Text("删除会话", color = MaterialTheme.colorScheme.error)
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    modifier = Modifier.padding(bottom = Space.xl).clickable {
                        pendingDelete = session
                        actionTarget = null
                    },
                )
            }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话？") },
            text = { Text("「${session.title}」的 ${session.messageCount} 条消息会一起删掉，不能撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.deleteSession(session.id)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}
