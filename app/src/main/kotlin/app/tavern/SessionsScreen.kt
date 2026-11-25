package app.tavern

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tavern.core.regex.RegexScript
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
    val statusBar = statusBarTop()
    // 拉到头还能继续拖（带阻尼），松手弹回
    val overscroll = rememberRubberOverscroll()
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    // 动作单由页面骨架渲染（要画在录图层之外才能真磨砂），这里只"请求"
    val sheet = LocalSheetController.current

    // 分组与时间标签都以"进入这一屏的那一刻"为准：不在滚动途中重算，行标签才不会半路跳变
    val now = remember(state.sessions) { System.currentTimeMillis() }
    val listItems = remember(state.sessions, now, state.displayRegexScripts) {
        buildSessionItems(state.sessions, now, state.displayRegexScripts)
    }

    if (!state.sessionsLoaded) {
        // 还没读到库：什么也不写。"还没有会话"是**结论**，不能在加载完成前先说出来
        // （用户 2026-11-24 报的"进来空白半秒，然后突然刷出来"）。
        return
    }

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
                // 页头 + 分组标题自己的 16dp 上边距已经够，不再另加 8（用户 2026-11-24：
                // "和标题离得有点远，往上一点点"）
                top = PageHeaderHeight + statusBar,
                bottom = Space.l + bottomPadding,
            ),
        ) {
            items(listItems, key = { it.key }) { item ->
                when (item) {
                    is SessionListItem.Day -> SectionLabel(
                        text = item.label,
                        // 与行内文字左缘对齐（面板 16dp + 行内 12dp），标题不会比内容更靠边
                        modifier = Modifier.padding(start = Space.l + RowToken.padH),
                    )

                    is SessionListItem.Session -> GroupPiece(
                        piece = item.piece,
                        modifier = Modifier.padding(horizontal = Space.l),
                        dividerInset = RowToken.dividerInsetWithAvatar,
                        // 置顶组：填充改成描边色、片间用底色当"白线"（用户 2026-11-25 第 3 点）
                        fill = if (item.session.pinned) {
                            LocalTavernPalette.current.cardBorder
                        } else {
                            null
                        },
                        dividerColor = if (item.session.pinned) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            null
                        },
                    ) {
                        ListRow(
                            title = item.session.cardName,
                            subtitle = item.subtitle,
                            meta = item.meta,
                            leading = { RowAvatar(item.session.cardId, item.session.cardName) },
                            onClick = { viewModel.openSession(item.session.id) },
                            onLongClick = {
                                sheet.show(
                                    title = item.session.title,
                                    actions = listOfNotNull(
                                        SheetAction(
                                            if (item.session.pinned) "取消置顶" else "置顶",
                                        ) {
                                            sheet.dismiss()
                                            viewModel.setSessionPinned(
                                                item.session.id,
                                                !item.session.pinned,
                                            )
                                        },
                                        SheetAction("删除会话", destructive = true) {
                                            pendingDelete = item.session
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { session ->
        ConfirmDeleteDialog(
            title = "删除会话？",
            message = "「${session.title}」的 ${session.messageCount} 条消息会一起删掉，不能撤销。",
            onConfirm = {
                pendingDelete = null
                viewModel.deleteSession(session.id)
            },
            onDismiss = { pendingDelete = null },
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
private fun buildSessionItems(
    sessions: List<ChatSession>,
    now: Long,
    // 显示层正则：最后一条消息的预览也要清掉标记（用户 2026-11-25 补充）
    scripts: List<RegexScript>,
): List<SessionListItem> {
    val result = mutableListOf<SessionListItem>()
    // 置顶单独一组排最前（用户 2026-11-25 第 13 条）：不和"今天/更早"混在一起，
    // 否则一条老会话置顶后会被塞进「更早」，看着像没生效。组内仍按 updated_at 倒序。
    val pinned = sessions.filter { it.pinned }
    val rest = sessions.filterNot { it.pinned }
    if (pinned.isNotEmpty()) {
        result += SessionListItem.Day("置顶")
        appendSessionRows(pinned, now, scripts, result)
    }
    var index = 0
    while (index < rest.size) {
        val day = sessionDay(rest[index].updatedAt, now)
        var end = index
        while (end < rest.size && sessionDay(rest[end].updatedAt, now) == day) {
            end += 1
        }
        result += SessionListItem.Day(day.label)
        appendSessionRows(rest.subList(index, end), now, scripts, result)
        index = end
    }
    return result
}

/** 一组会话 → 列表行（片型按组内位置算；置顶组和日内组的规则一样）。 */
private fun appendSessionRows(
    rows: List<ChatSession>,
    now: Long,
    scripts: List<RegexScript>,
    into: MutableList<SessionListItem>,
) {
    rows.forEachIndexed { i, session ->
        into += SessionListItem.Session(
            session = session,
            subtitle = previewLine(
                session.lastMessage?.let { displayPlain(it, scripts) },
                session.lastFromUser,
            ),
            // 行尾只写时间：是不是置顶由分组标题说了，行里再标一遍是噪音（用户 2026-11-25 第 2 点）
            meta = sessionTimeLabel(session.updatedAt, now),
            piece = when {
                rows.size == 1 -> RowPiece.Single
                i == 0 -> RowPiece.Top
                i == rows.lastIndex -> RowPiece.Bottom
                else -> RowPiece.Middle
            },
        )
    }
}
