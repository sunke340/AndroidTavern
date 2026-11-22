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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val listState = rememberLazyListState()

    val itemCount = state.transcript.size + if (state.streamingText.isNotEmpty()) 1 else 0

    // 用户是否停在底部。串流时只有"本来就在底部"才跟着走，
    // 否则每来一个 token 都抢滚动，会把正在上翻的人拽回来，也会让列表反复重排（画面闪）。
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 2
        }
    }

    // 消息条数变化（新消息/切会话）→ 动画滚到底
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    // 流式增长 → 合帧贴底：不用动画（动画追不上 token），并且 60ms 内最多触发一次
    var lastFollowAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.streamingText.length) {
        if (!state.isStreaming || itemCount == 0 || !atBottom) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - lastFollowAt < 60L) return@LaunchedEffect
        lastFollowAt = now
        listState.scrollToItem(itemCount - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = viewModel::closeChat) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(
                            state.cardName.ifBlank { "聊天" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            state.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::toggleRequest) {
                        Text(if (state.showRequest) "收请求" else "请求")
                    }
                },
            )
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
            if (state.showRequest) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(Radius.card),
                    border = BorderStroke(1.dp, palette.cardBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(Space.m),
                    ) {
                        Text("这次实际发出去的内容", style = MaterialTheme.typography.labelSmall)
                        Text(
                            state.requestPreview.ifEmpty { "（还没发过）" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                if (state.transcript.isEmpty() && state.streamingText.isEmpty()) {
                    item { EmptyHint("还没有消息。发一句开始；点右上「请求」可以看到实际发出去的提示词。") }
                }
                items(state.transcript) { message ->
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
                if (state.streamingText.isNotEmpty()) {
                    item {
                        MessageRow(
                            message = ChatMessage(ChatRole.Assistant, state.streamingText),
                            style = state.effectiveMessageStyle,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(Motion.Fast),
                                placementSpec = null,
                                fadeOutSpec = null,
                            ),
                            showCursor = true,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(Radius.button),
                    border = BorderStroke(1.dp, palette.cardBorder),
                ) {
                    BasicTextField(
                        value = state.input,
                        onValueChange = viewModel::onInputChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.padding(horizontal = Space.m, vertical = 11.dp),
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
                        FilledIconButton(
                            onClick = viewModel::stop,
                            modifier = Modifier.size(44.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
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
                    } else {
                        FilledIconButton(
                            onClick = viewModel::send,
                            modifier = Modifier.size(44.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                        }
                    }
                }
            }
        }
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
