package app.tavern

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole

/**
 * 聊天页（全屏二级页）。
 *
 * 动效只做三件有信息量的事：新消息入场、流式光标、发送↔停止的按钮形变。
 * 另外会自动跟到底部（正在流式时瞬时贴底，避免动画追不上 token；平时用动画）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val palette = LocalTavernPalette.current
    val glassOn = state.visualEffects == VisualEffects.Glass
    val background = MaterialTheme.colorScheme.background

    GlassDetailScaffold(
        title = state.cardName.ifBlank { "聊天" },
        subtitle = state.status,
        onBack = viewModel::closeChat,
        glass = glassOn,
        // 聊天要能滚到玻璃页头下面，所以让位交给列表的 contentPadding
        contentTopInset = 0.dp,
        actions = {
            var menuOpen by remember { mutableStateOf(false) }
            CapsuleIcon(icon = Icons.Filled.MoreVert, contentDescription = "更多") { menuOpen = true }
            val activeProfile = state.profiles.firstOrNull { it.id == state.activeProfileId }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // 只是"看清现在用的哪套连接"，点击应该去哪儿（编辑档案）留到 D 批
                DropdownMenuItem(
                    text = { Text("模型档案：${activeProfile?.name ?: "未配置"}") },
                    enabled = false,
                    onClick = {},
                )
                DropdownMenuItem(
                    text = { Text("查看本次请求") },
                    onClick = {
                        menuOpen = false
                        viewModel.toggleRequest()
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            // reverseLayout：列表的"锚点"是底部，新消息天然出现在眼前，
            // 完全不需要滚动跟随——串流时也就不再有"每 60ms 跳一次"造成的整屏抽动。
            LazyColumn(
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(top = DetailHeaderHeight),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                if (state.streamingText.isNotEmpty()) {
                    item {
                        MessageRow(
                            message = ChatMessage(ChatRole.Assistant, state.streamingText),
                            style = state.effectiveMessageStyle,
                            showCursor = true,
                        )
                    }
                }
                if (state.transcript.isEmpty() && state.streamingText.isEmpty()) {
                    item { EmptyHint("还没有消息。发一句开始；点右上「请求」可以看到实际发出去的提示词。") }
                }
                items(state.transcript.asReversed()) { message ->
                    MessageRow(
                        message = message,
                        style = state.effectiveMessageStyle,
                        // 只做淡入/淡出，不做位移动画：串流时列表每帧都在长高，
                        // 位移动画会不断重放，整屏看起来在闪。
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(Motion.Base, easing = Motion.Decelerate),
                            placementSpec = null,
                            fadeOutSpec = tween(Motion.Fast),
                        ),
                    )
                }
            }

            // 输入区脚下"雾化"：消息在这一块上方就淡出，胶囊才是浮在空处
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val extra = 64.dp.toPx()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.5f to background.copy(alpha = 0.82f),
                                1f to background,
                            ),
                            topLeft = Offset(0f, -extra),
                            size = Size(size.width, size.height + extra),
                        )
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    // 输入框也做成玻璃胶囊。注意它自己在录制范围里，所以 frost 传 null：
                    // 采样自己的图层会变成自反馈（越糊越白）。这里背后只有页面底色，不采也没损失。
                    GlassSurface(
                        frost = null,
                        glass = glassOn,
                        shape = RoundedCornerShape(Radius.pill),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BasicTextField(
                                value = state.input,
                                onValueChange = viewModel::onInputChange,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l),
                                decorationBox = { inner ->
                                    if (state.input.isEmpty()) {
                                        Text(
                                            "说点什么",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                },
                            )
                        }
                    }

                    // 发送 ↔ 停止：同一个按钮位只换形态，不换位置
                    AnimatedContent(
                        targetState = state.isStreaming,
                        transitionSpec = {
                            (fadeIn(tween(Motion.Fast)) + scaleIn(tween(Motion.Fast), initialScale = 0.8f)) togetherWith
                                (fadeOut(tween(Motion.Fast)) + scaleOut(tween(Motion.Fast), targetScale = 0.8f))
                        },
                        label = "send-stop",
                    ) { streaming ->
                        if (streaming) {
                            GlassSurface(
                                frost = null,
                                glass = glassOn,
                                shape = CircleShape,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize().clickable(onClick = viewModel::stop),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // 停止用一小块方角，和"发送"的形状区分开，不依赖额外图标
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(
                                                MaterialTheme.colorScheme.onSurfaceVariant,
                                                RoundedCornerShape(2.dp),
                                            ),
                                    )
                                }
                            }
                        } else {
                            GlassSurface(
                                frost = null,
                                glass = glassOn,
                                shape = CircleShape,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize().clickable(onClick = viewModel::send),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "发送",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 请求面板：全屏弹层，可选中复制。以前内嵌在消息上方，白占一块地方。
    if (state.showRequest) {
        RequestDialog(state = state, onClose = viewModel::toggleRequest)
    }
}

/**
 * 消息渲染的唯一入口：样式切换只在这里分叉。
 * 气泡是默认样式，整行卡片保留为可选项（设置 → 外观里能切）。
 */
@Composable
private fun MessageRow(
    message: ChatMessage,
    style: MessageStyle,
    modifier: Modifier = Modifier,
    showCursor: Boolean = false,
) {
    when (style) {
        MessageStyle.Bubble -> BubbleRow(message, modifier, showCursor)
        MessageStyle.Card -> CardRow(message, modifier, showCursor)
    }
}

@Composable
private fun BubbleRow(message: ChatMessage, modifier: Modifier, showCursor: Boolean) {
    val palette = LocalTavernPalette.current
    val isUser = message.role == ChatRole.User

    Row(modifier = modifier.fillMaxWidth()) {
        if (isUser) Spacer(Modifier.weight(0.14f))
        Box(
            modifier = Modifier
                .weight(0.86f)
                .wrapContentWidth(if (isUser) Alignment.End else Alignment.Start),
        ) {
            Surface(
                color = if (isUser) palette.userBubble else MaterialTheme.colorScheme.surface,
                contentColor = if (isUser) palette.onUserBubble else MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(
                    topStart = Radius.bubble,
                    topEnd = Radius.bubble,
                    bottomStart = if (isUser) Radius.bubble else 5.dp,
                    bottomEnd = if (isUser) 5.dp else Radius.bubble,
                ),
                border = if (isUser) null else BorderStroke(1.dp, palette.cardBorder),
            ) {
                Row(modifier = Modifier.padding(horizontal = Space.m, vertical = 10.dp)) {
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                    if (showCursor) StreamingCursor()
                }
            }
        }
    }
}

/** 整行卡片样式（可选项）：信息密度低，适合长段落阅读。 */
@Composable
private fun CardRow(message: ChatMessage, modifier: Modifier, showCursor: Boolean) {
    val palette = LocalTavernPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, palette.cardBorder),
    ) {
        Column(modifier = Modifier.padding(Space.m)) {
            Text(
                text = if (message.role == ChatRole.User) "我" else "角色",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                if (showCursor) StreamingCursor()
            }
        }
    }
}

/** 流式输出时的光标：慢速闪烁，告诉用户"还在写"。 */
@Composable
private fun StreamingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursor-alpha",
    )
    Text(
        "▍",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.alpha(alpha),
    )
}

/**
 * 「本次请求」全屏弹层：把实际发出去的 messages 原样列出来，可全选复制。
 *
 * 这是排查兼容性最常用的工具——卡有没有注入、世界书有没有进来，一眼能看出。
 * 放在弹层里而不是嵌在消息区，是因为它只在排查时需要，不该常驻占版面。
 */
@Composable
private fun RequestDialog(state: ChatViewModel.UiState, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val text = state.requestPreview.ifEmpty { "（还没发过请求）" }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.s, vertical = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(start = Space.s)) {
                        Text("本次请求", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "共 ${text.count { it == '\n' } + 1} 行 · ${text.length} 字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                        Text("复制")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }

                HorizontalDivider(color = LocalTavernPalette.current.divider)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(Space.l),
                ) {
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }
        }
    }
}
