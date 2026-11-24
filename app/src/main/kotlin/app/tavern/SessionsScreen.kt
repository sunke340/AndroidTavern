package app.tavern

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tavern.data.chat.ChatSession

/**
 * 会话列表：最近活动在最上面，按「今天 / 更早」分成两组圆角面板。
 *
 * 每行的副行是**最后一条消息**（不再只是"日期 · 条数"）—— 会话列表唯一要回答的问题
 * 是"这场聊到哪了"。行尾给分级时间（今天只给时刻，一周内给周几，更早给月-日）。
 *
 * 删除走**长按**：行尾不再放删除按钮（那会让每行都很挤）。
 * 长按 → 动作单 → 删除 → 二次确认：动作单以后还能放"重命名 / 导出"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    // 滚动位置由上层持有：从二级页返回时列表不该回到顶部。
    listState: LazyListState = rememberLazyListState(),
    // 悬浮底栏压在内容上，列表底部要让出它的高度，否则最后一行被胶囊压住。
    bottomPadding: Dp = 0.dp,
) {
    // 页头一直铺到状态栏后面，内容要让出"状态栏 + 页头"的高度
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // 拉到头还能继续拖（带阻尼），松手弹回
    val overscroll = rememberRubberOverscroll()
    var actionTarget by remember { mutableStateOf<ChatSession?>(null) }
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }

    // 分组与时间标签都以"进入这一屏的那一刻"为准：不在滚动途中重算，行标签才不会半路跳变
    val now = remember(state.sessions) { System.currentTimeMillis() }
    val listItems = remember(state.sessions, now) { buildSessionItems(state.sessions, now) }

    if (state.sessions.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Space.l)
                .padding(top = PageHeaderHeight + Space.l + statusBar),
        ) {
            EmptyHint("还没有会话。点右上「＋」选一张卡开始。")
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().rubberOverscroll(overscroll),
            state = listState,
            contentPadding = PaddingValues(
                top = PageHeaderHeight + Space.s + statusBar,
                bottom = Space.l + bottomPadding,
            ),
        ) {
            items(listItems, key = { it.key }) { item ->
                when (item) {
                    is SessionListItem.Day -> SectionLabel(
                        text = item.label,
                        // 与行内文字左缘对齐（面板 16dp + 行内 12dp），标题不会比内容更靠边
                        modifier = Modifier.padding(start = Space.l + RowToken.padH, top = Space.xs),
                    )

                    is SessionListItem.Session -> GroupPiece(
                        piece = item.piece,
                        modifier = Modifier.padding(horizontal = Space.l),
                        dividerInset = RowToken.dividerInsetWithAvatar,
                    ) {
                        ListRow(
                            title = item.session.cardName,
                            subtitle = item.subtitle,
                            meta = item.meta,
                            leading = { RowAvatar(item.session.cardId, item.session.cardName) },
                            onClick = { viewModel.openSession(item.session.id) },
                            onLongClick = { actionTarget = item.session },
                        )
                    }
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

/** 懒列表里的一行/一条分组标题。 */
private sealed interface SessionListItem {
    val key: String

    data class Day(val label: String) : SessionListItem {
        override val key: String get() = "day-$label"
    }

    data class Session(
        val session: ChatSession,
        /** 最后一条消息压成一行；一条消息都没有时为 null（只显示标题）。 */
        val subtitle: String?,
        val meta: String,
        /** 行在分组里的位置：决定圆角与边线。 */
        val piece: RowPiece,
    ) : SessionListItem {
        override val key: String get() = session.id
    }
}

/**
 * 把倒序的会话切成「今天 / 更早」两段，并算出每行在组里的位置。
 *
 * `sessions` 已由仓库按 `updated_at` 倒序给出，所以同一组一定是连续的，
 * 顺序扫一遍即可（不做二次排序，避免和 SQL 的排序口径打架）。
 */
private fun buildSessionItems(sessions: List<ChatSession>, now: Long): List<SessionListItem> {
    val result = mutableListOf<SessionListItem>()
    var index = 0
    while (index < sessions.size) {
        val day = sessionDay(sessions[index].updatedAt, now)
        var end = index
        while (end < sessions.size && sessionDay(sessions[end].updatedAt, now) == day) {
            end += 1
        }
        result += SessionListItem.Day(day.label)
        val count = end - index
        for (i in index until end) {
            val session = sessions[i]
            result += SessionListItem.Session(
                session = session,
                subtitle = previewLine(session.lastMessage, session.lastFromUser),
                meta = sessionTimeLabel(session.updatedAt, now),
                piece = when {
                    count == 1 -> RowPiece.Single
                    i == index -> RowPiece.Top
                    i == end - 1 -> RowPiece.Bottom
                    else -> RowPiece.Middle
                },
            )
        }
        index = end
    }
    return result
}
