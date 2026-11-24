package app.tavern

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tavern.data.chat.ChatSession

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
    // 系统「移除动画」：四标签之间只做淡化，不做横向位移
    val reduceMotion = rememberReduceMotion()
    val detail = state.detail
    val cardRef = state.cards.firstOrNull { it.id == cardId }
    val sessions = state.sessions.filter { it.cardId == cardId }

    // 标签页存在 UiState 里：进聊天/进配置二级页时详情页会被移出组合树，
    // 局部 remember 会归零 —— 表现就是"从会话 tab 进去，返回却停在简介"（用户 2026-11-24 报）。
    val tabIndex = DetailTab.entries.indexOf(state.detailTab).coerceAtLeast(0)
    var confirmDeleteCard by remember { mutableStateOf(false) }
    // 动作单交给页面骨架渲染（GlassDetailScaffold 的浮层），这里只请求
    val sheet = LocalSheetController.current
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
                    onClick = { viewModel.startSessionForCard(cardId, leaveDetail = true) },
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
                        onClick = { viewModel.selectDetailTab(tab) },
                        text = { Text(tab.label) },
                    )
                }
            }

            AnimatedContent(
                targetState = tabIndex,
                transitionSpec = {
                    val forward = targetState > initialState
                    val enter: EnterTransition =
                        if (reduceMotion) {
                            fadeIn(tween(Motion.Base, easing = Motion.Decelerate))
                        } else {
                            fadeIn(tween(Motion.Base, easing = Motion.Decelerate)) +
                                slideInHorizontally(tween(Motion.Base, easing = Motion.Decelerate)) { w ->
                                    if (forward) w / 10 else -w / 10
                                }
                        }
                    val exit: ExitTransition =
                        if (reduceMotion) {
                            fadeOut(tween(Motion.Fast))
                        } else {
                            fadeOut(tween(Motion.Fast)) +
                                slideOutHorizontally(tween(Motion.Fast)) { w ->
                                    if (forward) -w / 10 else w / 10
                                }
                        }
                    enter togetherWith exit
                },
                label = "detail-tab",
            ) { index ->
                when (DetailTab.entries[index]) {
                    DetailTab.Description -> {
                        // 先用角色卡列表里已经有的描述顶上：完整详情要读卡文件才到（几十 ms），
                        // 让长描述在**转场那一帧**就排好版；等 detail 到了文本没变，Text 不会重排。
                        // 不然长描述会在动画中途才排版一次 —— 那一下正好吃掉转场的好几帧。
                        val text = (detail?.description ?: cardRef?.description).orEmpty()
                        ScrollArea {
                            Text(
                                text.ifBlank {
                                    if (detail == null) "读取中…" else "（这张卡没有描述）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    DetailTab.Greeting -> ScrollArea { GreetingTab(detail) }

                    // 行数由数据决定 → 必须懒加载（同世界书条目页）。这个 tab 的选择是会保留的，
                    // 返回详情页时若它正处于选中态，ListGroup 会把整张会话表（几十行 + 头像）
                    // 在第一帧里组合出来，正好压在转场动画上。
                    DetailTab.Sessions -> LazyColumn(
                        modifier = Modifier.fillMaxSize().rubberOverscroll(rememberRubberOverscroll()),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        if (sessions.isEmpty()) {
                            item { Text("这张卡还没有会话", style = MaterialTheme.typography.bodySmall) }
                        } else {
                            itemsIndexed(sessions) { index, session ->
                                GroupPiece(
                                    piece = rowPieceAt(index, sessions.size),
                                    dividerInset = RowToken.dividerInsetWithAvatar,
                                ) {
                                    ListRow(
                                        title = session.cardName,
                                        subtitle = previewLine(
                                            session.lastMessage,
                                            session.lastFromUser,
                                        ),
                                        meta = sessionTimeLabel(session.updatedAt),
                                        leading = { RowAvatar(session.cardId, session.cardName) },
                                        onClick = { viewModel.openSession(session.id) },
                                        onLongClick = {
                                            sheet.show(
                                                title = session.title,
                                                actions = listOf(
                                                    SheetAction("删除会话", destructive = true) {
                                                        pendingDeleteSession = session
                                                    },
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    DetailTab.Config -> ScrollArea {
                        val bookCount = detail?.bookEntries?.size ?: 0
                        val disabledCount = detail?.offUids?.size ?: 0
                        val forcedCount = detail?.forcedUids?.size ?: 0
                        val globalStyle = if (state.messageStyle == MessageStyle.Bubble) "气泡" else "整行卡片"
                        val styleLabel = when (detail?.messageStyle) {
                            null -> "跟随全局（$globalStyle）"
                            MessageStyle.Bubble -> "气泡"
                            MessageStyle.Card -> "整行卡片"
                        }

                        // 三级继承的中间层：这一层选的会作为"这张卡以后开的新会话"的默认值
                        val cardProfile = state.profiles.firstOrNull {
                            it.id == (state.cardOverride.profileId ?: state.activeProfileId)
                        }
                        val cardProfileName = cardProfile?.name ?: "未配置"
                        val cardProfileSrc = if (state.cardOverride.profileId != null) "角色卡" else "全局"
                        val cardBooks = effectiveBookNames(state)
                        val cardBooksSrc = if (state.cardOverride.worldBookNames != null) "角色卡" else "全局"
                        val cardRegex = effectiveRegexNames(state)
                        val cardRegexSrc = if (state.cardOverride.regexIds != null) "角色卡" else "全局"
                        val cardPreset = state.cardOverride.presetName ?: state.activePreset ?: "内置默认"
                        val cardPresetSrc = if (state.cardOverride.presetName != null) "角色卡" else "全局"

                        ListGroup(count = 7) { index, _ ->
                            when (index) {
                                0 -> SettingLinkRow(
                                    title = "内嵌世界书",
                                    value = if (bookCount == 0) {
                                        "这张卡没有内嵌书"
                                    } else {
                                        buildString {
                                            append("$bookCount 条 · 已关闭 $disabledCount 条")
                                            if (forcedCount > 0) append(" · 强制 $forcedCount 条")
                                        }
                                    },
                                ) { viewModel.openCardConfigPage(CardConfigPage.WorldBook) }

                                1 -> SettingLinkRow("模型", "$cardProfileName · $cardProfileSrc") {
                                    showModelSheet(sheet, state, viewModel, ConfigScope.Card)
                                }

                                2 -> SettingLinkRow("世界书", "参与 ${cardBooks.size} 本 · $cardBooksSrc") {
                                    showWorldBookSheet(sheet, state, viewModel, ConfigScope.Card, cardBooks)
                                }

                                3 -> SettingLinkRow("正则", "参与 ${cardRegex.size} 条 · $cardRegexSrc") {
                                    showRegexSheet(sheet, state, viewModel, ConfigScope.Card, cardRegex)
                                }

                                4 -> SettingLinkRow("预设", "$cardPreset · $cardPresetSrc") {
                                    showPresetSheet(sheet, state, viewModel, ConfigScope.Card)
                                }

                                5 -> SettingLinkRow("对话样式", styleLabel) {
                                    viewModel.openCardConfigPage(CardConfigPage.MessageStyle)
                                }

                                else -> SettingLinkRow("关于这张卡", "作者 / 版本 / 规格 / 标签") {
                                    viewModel.openCardConfigPage(CardConfigPage.Info)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDeleteCard) {
        ConfirmDeleteDialog(
            title = "删除角色卡？",
            message = "「${cardRef?.name ?: "这张卡"}」和它的 ${sessions.size} 个会话会一起删掉，" +
                "卡文件也会从应用里删除，不能撤销。",
            onConfirm = {
                confirmDeleteCard = false
                viewModel.deleteCard(cardId)
            },
            onDismiss = { confirmDeleteCard = false },
        )
    }

    pendingDeleteSession?.let { session ->
        ConfirmDeleteDialog(
            title = "删除会话？",
            message = "「${session.title}」的 ${session.messageCount} 条消息会一起删掉，不能撤销。",
            onConfirm = {
                pendingDeleteSession = null
                viewModel.deleteSession(session.id)
            },
            onDismiss = { pendingDeleteSession = null },
        )
    }
}

@Composable
private fun ScrollArea(content: @Composable () -> Unit) {
    // 四个标签各自是一块可滚动面：每个都要橡皮筋（和会话/角色卡页一致）
    val overscroll = rememberRubberOverscroll()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .rubberOverscroll(overscroll)
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
