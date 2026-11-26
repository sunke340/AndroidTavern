package app.tavern

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tavern.core.regex.RegexEngine
import app.tavern.core.regex.RegexPlacement
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
    // 动作单 / 确认框交给页面骨架渲染。控制器**由本页创建**再传进骨架：这一层在骨架之前，
    // 直接读 CompositionLocal 会拿到没有宿主的默认实例（2026-11-25 真机：配置行点不开、
    // 会话行长按没反应）。内容区的子组件仍读 Local，拿到的是同一个实例。
    val sheet = remember { SheetController() }
    val confirm = remember { ConfirmController() }
    var pendingDeleteSession by remember { mutableStateOf<ChatSession?>(null) }

    GlassDetailScaffold(
        // 顶栏只说明"这是哪一页"；卡名在下面的卡头里出现一次就够
        title = "角色详情",
        onBack = viewModel::closeDetail,
        glass = state.visualEffects.effective() == VisualEffects.Glass,
        // 唯一"不穿页头"的具名例外：卡头是常驻海报，整页内容用 `.padding(padding)` 让位在
        // 滚动容器外面（其他二级页都把 top 用在滚动内容里，才会有内容从页头底下过的观感）。
        headerMode = DetailHeaderMode.Translucent,
        sheetController = sheet,
        confirmController = confirm,
        actions = {
            // 胶囊点开 = 液态玻璃菜单（需求批 E 原型的第二处）。宿主在 DetailPage 的骨架里。
            val capsuleMenu = LocalCapsuleMenu.current
            var anchor by remember { mutableStateOf(Rect.Zero) }
            // 导入聊天：.jsonl 的 MIME 各家文件管理器报得不一样，多收几个类型
            val importChatLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri -> if (uri != null) viewModel.importChat(cardId, uri) }
            Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
                CapsuleIcon(icon = Icons.Filled.MoreVert, contentDescription = "更多") {
                    capsuleMenu.show(
                        anchor = anchor,
                        items = listOf(
                            CapsuleMenuItem("编辑卡片") { viewModel.openCardEditor() },
                            CapsuleMenuItem("导入聊天记录") {
                                importChatLauncher.launch(
                                    arrayOf("application/jsonl", "text/plain", "application/octet-stream"),
                                )
                            },
                            CapsuleMenuItem("删除这张卡") { confirmDeleteCard = true },
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ---- 卡头：常驻，不随标签滚动 ----
            //
            // 版式（用户 2026-11-25 第 4 条：保留 TabRow，只重做卡头与信息）：
            // 原来 96dp 小头像 + 右边三行小字，信息量和"这是一张卡"的观感都弱。
            // 现在改成**大图压字**：名字与元信息落在图底的渐变上，主按钮紧跟图下。
            val heroName = detail?.name ?: cardRef?.name ?: cardId
            val heroMeta = buildString {
                append(if (detail?.imported == true) "导入的卡" else "随包带的卡")
                append(" · 描述 ${detail?.description?.length ?: cardRef?.description?.length ?: 0} 字")
                val books = detail?.bookEntries?.size ?: 0
                if (books > 0) append(" · 世界书 $books 条")
            }
            Column(
                modifier = Modifier
                    .padding(horizontal = Space.l)
                    .padding(top = Space.s),
            ) {
                // 卡头（用户 2026-11-25 第 14 条）：200dp 的横条 + Crop 会把竖图裁掉大截，
                // 改成"模糊底 + 完整竖图"的海报版式（见 CardHero）。高度提到 260dp，
                // 竖图在 3:4 下能占到 195×260，四周留出的糊面当名字的底。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(18.dp)),
                ) {
                    CardHero(
                        cardId = cardId,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 字压在图上必须自带底：浅色图上白字会糊成一片
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    0.42f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.62f),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(Space.l),
                    ) {
                        Text(
                            heroName,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                        )
                        Text(
                            heroMeta,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.86f),
                            modifier = Modifier.padding(top = Space.xs),
                        )
                    }
                }

                Button(
                    onClick = { viewModel.startSessionForCard(cardId, leaveDetail = true) },
                    modifier = Modifier.fillMaxWidth().padding(top = Space.m),
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
                        // 简介也走显示管线（用户 2026-11-25 补充）：正则清标记 + `<i>/<b>` 内联样式，
                        // 与气泡/开场白同一套；卡文件本身不动。
                        val primary = MaterialTheme.colorScheme.primary
                        val scripts = state.displayRegexScripts
                        val shown = remember(text, scripts, primary) {
                            if (text.isBlank()) {
                                null
                            } else {
                                val display = if (scripts.isEmpty()) {
                                    text
                                } else {
                                    RegexEngine().applyAll(scripts, text, RegexPlacement.AiOutput)
                                }
                                markupText(display, primary)
                            }
                        }
                        ScrollArea {
                            if (shown != null) {
                                Text(shown, style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text(
                                    if (detail == null) "读取中…" else "（这张卡没有描述）",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    DetailTab.Greeting -> ScrollArea {
                        GreetingTab(detail, viewModel, state.cardOverride.greetingIndex ?: 0, state)
                    }

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
                                            session.lastMessage?.let {
                                                displayPlain(it, state.displayRegexScripts)
                                            },
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

                        val cardPersonaStored = state.cardOverride.personaId
                        val cardPersonaSrc = if (cardPersonaStored != null) "角色卡" else "全局"
                        val cardPersonaName = state.effectivePersona?.name ?: "未设置"
                        ListGroup(count = 9) { index, _ ->
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
                                    viewModel.openConfigPicker(ConfigScope.Card, ConfigKind.Model)
                                }

                                2 -> {
                                    val o = state.cardOverride
                                    val local = listOf(
                                        o.temperature,
                                        o.maxTokens,
                                        o.topP,
                                        o.frequencyPenalty,
                                        o.presencePenalty,
                                    ).any { it != null }
                                    SettingLinkRow(
                                        "采样",
                                        "temperature ${state.effectiveTemperature} · " +
                                            if (local) "这张卡" else "跟随上级",
                                    ) {
                                        viewModel.openConfigPicker(ConfigScope.Card, ConfigKind.Sampling)
                                    }
                                }

                                3 -> SettingLinkRow("世界书", "参与 ${cardBooks.size} 本 · $cardBooksSrc") {
                                    viewModel.openConfigPicker(ConfigScope.Card, ConfigKind.WorldBook)
                                }

                                4 -> SettingLinkRow("正则", "参与 ${cardRegex.size} 条 · $cardRegexSrc") {
                                    viewModel.openConfigPicker(ConfigScope.Card, ConfigKind.Regex)
                                }

                                5 -> SettingLinkRow("预设", "$cardPreset · $cardPresetSrc") {
                                    viewModel.openConfigPicker(ConfigScope.Card, ConfigKind.Preset)
                                }

                                6 -> SettingLinkRow("人设", "$cardPersonaName · $cardPersonaSrc") {
                                    viewModel.openConfigPicker(ConfigScope.Card, ConfigKind.Persona)
                                }

                                7 -> SettingLinkRow("对话样式", styleLabel) {
                                    viewModel.openCardConfigPage(CardConfigPage.MessageStyle)
                                }

                                else -> SettingLinkRow("关于这张卡", "作者 / 版本 / 规格 / 标签") {
                                    viewModel.openCardConfigPage(CardConfigPage.Info)
                                }
                            }
                        }

                        // 卡级思考强度不在这里单独占一行：它在「模型」页顶部
                        // （用户 2026-11-25 第 5 条：两处重复，只留模型内那一处）
                    }
                }
            }
        }
    }

    if (confirmDeleteCard) {
        ConfirmDeleteDialog(
            controller = confirm,
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
            controller = confirm,
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
private fun GreetingTab(
    detail: CardDetail?,
    viewModel: ChatViewModel,
    defaultIndex: Int,
    state: ChatViewModel.UiState,
) {
    // 空串要剔掉：有的卡在 alternateGreetings 里留了空项，翻过去就是一条空气泡
    val greetings = (listOf(detail?.firstMessage.orEmpty()) + detail?.alternateGreetings.orEmpty())
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (greetings.isEmpty()) {
        Text("（这张卡没有开场白）", style = MaterialTheme.typography.bodySmall)
        return
    }
    val preferred = defaultIndex.coerceIn(0, greetings.lastIndex)
    var index by remember(greetings.size, preferred) { mutableIntStateOf(preferred) }

    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        // 翻页箭头照聊天页那一套：先读、读到了再决定要不要设为默认
        Row(verticalAlignment = Alignment.CenterVertically) {
            GreetingChevron("‹", enabled = index > 0) { index -= 1 }
            Text(
                "${index + 1}/${greetings.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.s),
            )
            GreetingChevron("›", enabled = index < greetings.lastIndex) { index += 1 }
            Spacer(Modifier.weight(1f))
            // 两个状态**占同样大小**（用户 2026-11-25 第 9 条）：以前只在"不是默认"时才出现
            // 「设为默认」，它一冒出来就把下面的开场白正文往下压一截。现在默认那条也放一个
            // 同尺寸的灰字胶囊「当前默认」，翻页时高度不变。
            // 2026-11-26：原文案用 TextButton，Material 的最小高度 40dp 把这一行撑得太高，
            // 换成 26dp 的细胶囊（上下各让 7dp），一眼看过去不再是一条"宽按钮"。
            DefaultGreetingChip(
                isDefault = index == preferred,
                onClick = { viewModel.useCardGreeting(index) },
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            // 和聊天气泡同一套显示管线：显示层正则（含内置"清标签/强调"）→ 内联标签渲染
            // （用户 2026-11-25 第 7 条：正则也要作用到开场白）。只影响显示，卡文件不动。
            val scripts = state.displayRegexScripts
            val display = if (scripts.isEmpty()) {
                greetings[index]
            } else {
                RegexEngine().applyAll(scripts, greetings[index], RegexPlacement.AiOutput)
            }
            Text(
                markupText(display, MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(Space.m),
            )
        }
    }
}

/**
 * 开场白页的「设为默认 / 当前默认」：一枚 **26dp 高的细胶囊**，两个状态同尺寸。
 *
 * 之前用 Material3 `TextButton`，它的最小高度是 40dp —— 在只有一行箭头 + 页码的横排里
 * 显得上下各多出一截（用户 2026-11-26："上下长度太长，薄一点"）。
 * 现在高度由文字决定、上下各留 3dp，翻页时那句「当前默认」也不会把正文往下压。
 */
@Composable
private fun DefaultGreetingChip(isDefault: Boolean, onClick: () -> Unit) {
    val palette = LocalTavernPalette.current
    val shape = RoundedCornerShape(Radius.pill)
    Box(
        modifier = Modifier
            .heightIn(min = 26.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, palette.cardBorder, shape)
            .clickable(enabled = !isDefault, onClick = onClick)
            .padding(horizontal = Space.m, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isDefault) "当前默认" else "设为默认",
            style = MaterialTheme.typography.labelSmall,
            color = if (isDefault) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}
