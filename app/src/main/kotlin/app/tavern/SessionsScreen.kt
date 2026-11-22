package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavern.data.chat.ChatSession

/**
 * 会话列表：最近活动排在最上面。点一行进聊天。
 *
 * 视觉按 C 方向：用 1px 分隔线的列表行，不再给每条套卡片（卡片叠分隔线会造成双重分隔）。
 */
@Composable
fun SessionsScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }

    PageScaffold(
        title = "会话",
        action = { PrimaryAction("＋ 新建", onClick = viewModel::newSession) },
    ) {
        if (state.sessions.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(Space.l)) {
                EmptyHint("还没有会话。去「角色」里选一张卡，进详情后开新会话。")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = Space.s, bottom = Space.l),
                verticalArrangement = Arrangement.Top,
            ) {
                items(state.sessions, key = { it.id }) { session ->
                    ListRow(
                        title = session.cardName,
                        subtitle = "${formatSessionTime(session.updatedAt)} · ${session.messageCount} 条",
                        leading = {
                            CardAvatar(
                                cardId = session.cardId,
                                fallbackText = session.cardName,
                                modifier = Modifier.size(40.dp),
                            )
                        },
                        trailing = {
                            IconButton(onClick = { pendingDelete = session }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除会话",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { viewModel.openSession(session.id) },
                    )
                }
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
