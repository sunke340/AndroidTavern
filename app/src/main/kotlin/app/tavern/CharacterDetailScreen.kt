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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDeleteCard by remember { mutableStateOf(false) }
    var pendingDeleteSession by remember { mutableStateOf<ChatSession?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = viewModel::closeDetail) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 顶栏只说明"这是哪一页"；卡名在下面的卡头里出现一次就够
                title = {
                    Text(
                        "角色详情",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
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
            )
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
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clickable { viewModel.openSession(session.id) },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(session.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${session.messageCount} 条",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    TextButton(onClick = { pendingDeleteSession = session }) { Text("删") }
                                }
                            }
                        }
                    }

                    DetailTab.Config -> ScrollArea {
                        ConfigTab(detail = detail, cardId = cardId, viewModel = viewModel)
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
        Text(
            "只读：选择备选开场白要等 swipe 做完（P0-6）。新建会话用的永远是第一条。",
            style = MaterialTheme.typography.labelSmall,
        )
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

@Composable
private fun ConfigTab(detail: CardDetail?, cardId: String, viewModel: ChatViewModel) {
    val entries = detail?.bookEntries.orEmpty()
    val disabled = detail?.disabledEntries.orEmpty()

    SectionTitle("卡内世界书（${entries.size} 条）")
    if (entries.isEmpty()) {
        Text("这张卡没有内嵌世界书", style = MaterialTheme.typography.bodySmall)
    } else {
        entries.forEach { entry ->
            val enabled = entry.index !in disabled
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setBookEntryEnabled(cardId, entry.index, !enabled) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = { viewModel.setBookEntryEnabled(cardId, entry.index, it) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.comment, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        entry.keys.joinToString("、").ifBlank { "（没有关键字）" },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Text(
            "世界书引擎还没实现（P1）：勾选会先存下来，等激活算法做好后才影响提示词。",
            style = MaterialTheme.typography.labelSmall,
        )
    }

    SectionTitle("额外世界书")
    Text(
        "暂不支持：需要先有世界书库与激活引擎（P1）。",
        style = MaterialTheme.typography.bodySmall,
    )

    SectionTitle("消息展示（这张卡）")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = detail?.messageStyle == null,
            onClick = { viewModel.setCardMessageStyle(cardId, null) },
            label = { Text("跟随默认") },
        )
        FilterChip(
            selected = detail?.messageStyle == MessageStyle.Card,
            onClick = { viewModel.setCardMessageStyle(cardId, MessageStyle.Card) },
            label = { Text("整行卡片") },
        )
        FilterChip(
            selected = detail?.messageStyle == MessageStyle.Bubble,
            onClick = { viewModel.setCardMessageStyle(cardId, MessageStyle.Bubble) },
            label = { Text("气泡（实验）") },
        )
    }
    if (detail?.messageStyle == MessageStyle.Bubble) {
        Text(
            "气泡样式还没实现，暂时按整行卡片显示。",
            style = MaterialTheme.typography.labelSmall,
        )
    }

    SectionTitle("卡信息")
    InfoLine("作者", detail?.creator.orEmpty().ifBlank { "（未填）" })
    InfoLine("版本", detail?.characterVersion.orEmpty().ifBlank { "（未填）" })
    InfoLine(
        "规格",
        listOfNotNull(detail?.spec, detail?.specVersion).joinToString(" / ").ifBlank { "（V1 扁平卡）" },
    )
    InfoLine("标签", detail?.tags.orEmpty().joinToString("、").ifBlank { "（无）" })
    InfoLine("人格", fieldState(detail?.personality))
    InfoLine("场景", fieldState(detail?.scenario))
    InfoLine("示例对话", fieldState(detail?.messageExample))
    InfoLine("备选开场白", "${detail?.alternateGreetings?.size ?: 0} 条")
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.size(width = 72.dp, height = 18.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/** 空字段说明白一点：很多卡把内容全写进描述，这里是空的其实是作者的写法。 */
private fun fieldState(value: String?): String =
    if (value.isNullOrBlank()) "（空 —— 作者可能写进了描述）" else "${value.length} 字"
