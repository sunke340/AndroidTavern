package app.tavern

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tavern.data.chat.ChatSession

/** 详情页的四个标签。删除卡这类破坏性操作不在这里，收进右上角「⋮」。 */
private enum class DetailTab(val label: String) {
    Description("简介"),
    Greeting("开场白"),
    Sessions("会话"),
    Config("配置"),
}

/**
 * 角色详情页。
 *
 * 版式：卡头（图 + 名字 + 元信息 + 开新会话按钮）常驻 → 标签栏 → 各标签自己滚动。
 * 「配置」是**只读 + 有限开关**：卡内嵌世界书的勾选、这张卡的消息样式。
 * 改卡字段（写回 PNG）是下一批，需要备份与写回校验。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterDetailScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val cardId = state.detailCardId ?: return
    val detail = state.detail
    val cardRef = state.cards.firstOrNull { it.id == cardId }
    val sessions = state.sessions.filter { it.cardId == cardId }

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var confirmDeleteCard by remember { mutableStateOf(false) }
    var actionTargetSession by remember { mutableStateOf<ChatSession?>(null) }
    var pendingDeleteSession by remember { mutableStateOf<ChatSession?>(null) }

    GlassDetailScaffold(
        // 顶栏只说明"这是哪一页"；卡名在下面的卡头里出现一次就够
        title = "角色详情",
        onBack = viewModel::closeDetail,
        glass = state.visualEffects.effective() == VisualEffects.Glass,
        actions = {
            var menuOpen by remember { mutableStateOf(false) }
            CapsuleIcon(icon = Icons.Filled.MoreVert, contentDescription = "更多") { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text("删除这张卡", color = MaterialTheme.colorScheme.error)
                    },
                    onClick = {
                        menuOpen = false
                        confirmDeleteCard = true
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ---- 卡头：常驻，不随标签滚动 ----
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = Space.l),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    CardAvatar(
                        cardId = cardId,
                        fallbackText = detail?.name ?: cardRef?.name.orEmpty(),
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            detail?.name ?: cardRef?.name ?: cardId,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            if (detail?.imported == true) "导入的卡" else "随包带的卡",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            "描述 ${detail?.description?.length ?: cardRef?.description?.length ?: 0} 字" +
                                if ((detail?.bookEntries?.size ?: 0) > 0) "｜世界书 ${detail?.bookEntries?.size} 条" else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                Button(
                    onClick = { viewModel.startSessionForCard(cardId) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("用这张卡开新会话") }
            }

            androidx.compose.foundation.layout.Spacer(Modifier.height(Space.m))

            TabRow(
                selectedTabIndex = tabIndex,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                DetailTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = { Text(tab.label) },
                    )
                }
            }

            AnimatedContent(
                targetState = tabIndex,
                transitionSpec = {
                    val forward = targetState > initialState
                    (
                        fadeIn(tween(Motion.Base, easing = Motion.Decelerate)) +
                            slideInHorizontally(tween(Motion.Base, easing = Motion.Decelerate)) { w ->
                                if (forward) w / 10 else -w / 10
                            }
                        ) togetherWith (
                        fadeOut(tween(Motion.Fast)) +
                            slideOutHorizontally(tween(Motion.Fast)) { w -> if (forward) -w / 10 else w / 10 }
                        )
                },
                label = "detail-tab",
            ) { index ->
                when (DetailTab.entries[index]) {
                    DetailTab.Description -> ScrollArea {
                        Text(
                            detail?.description?.ifBlank { "（这张卡没有描述）" } ?: "读取中…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    DetailTab.Greeting -> ScrollArea { GreetingTab(detail) }

                    DetailTab.Sessions -> ScrollArea {
                        if (sessions.isEmpty()) {
                            Text("这张卡还没有会话", style = MaterialTheme.typography.bodySmall)
                        }
                        sessions.forEach { session ->
                            ListRow(
                                title = session.cardName,
                                subtitle = "${formatSessionTime(session.updatedAt)} · ${session.messageCount} 条",
                                onClick = { viewModel.openSession(session.id) },
                                onLongClick = { actionTargetSession = session },
                            )
                        }
                    }

                    DetailTab.Config -> ScrollArea {
                        val bookCount = detail?.bookEntries?.size ?: 0
                        val disabledCount = detail?.disabledEntries?.size ?: 0
                        val globalStyle = if (state.messageStyle == MessageStyle.Bubble) "气泡" else "整行卡片"
                        val styleLabel = when (detail?.messageStyle) {
                            null -> "跟随全局（$globalStyle）"
                            MessageStyle.Bubble -> "气泡"
                            MessageStyle.Card -> "整行卡片"
                        }

                        SettingLinkRow(
                            title = "内嵌世界书",
                            value = if (bookCount == 0) {
                                "这张卡没有内嵌书"
                            } else {
                                "$bookCount 条 · 已关闭 $disabledCount 条"
                            },
                        ) { viewModel.openCardConfigPage(CardConfigPage.WorldBook) }

                        SettingLinkRow("对话样式", styleLabel) {
                            viewModel.openCardConfigPage(CardConfigPage.MessageStyle)
                        }
                        SettingLinkRow("关于这张卡", "作者 / 版本 / 规格 / 标签") {
                            viewModel.openCardConfigPage(CardConfigPage.Info)
                        }
                    }
                }
            }
        }
    }

    if (confirmDeleteCard) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCard = false },
            title = { Text("删除角色卡？") },
            text = {
                Text("会连带删除它的 ${sessions.size} 个会话，不能撤销。卡文件也会从应用里删掉。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteCard = false
                        viewModel.deleteCard(cardId)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteCard = false }) { Text("取消") }
            },
        )
    }

    actionTargetSession?.let { session ->
        ModalBottomSheet(
            onDismissRequest = { actionTargetSession = null },
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
                androidx.compose.material3.ListItem(
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
                    modifier = Modifier
                        .padding(bottom = Space.xl)
                        .clickable {
                            pendingDeleteSession = session
                            actionTargetSession = null
                        },
                )
            }
        }
    }

    pendingDeleteSession?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSession = null },
            title = { Text("删除会话？") },
            text = { Text("「${session.title}」的 ${session.messageCount} 条消息会一起删掉。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteSession = null
                        viewModel.deleteSession(session.id)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSession = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ScrollArea(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun GreetingTab(detail: CardDetail?) {
    val first = detail?.firstMessage.orEmpty()
    Text(
        first.ifBlank { "（这张卡没有开场白）" },
        style = MaterialTheme.typography.bodySmall,
    )

    val alternates = detail?.alternateGreetings.orEmpty()
    if (alternates.isNotEmpty()) {
        Text("备选开场白（${alternates.size}）", style = MaterialTheme.typography.labelMedium)
        alternates.forEachIndexed { index, text ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("备选 ${index + 1}", style = MaterialTheme.typography.labelSmall)
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
