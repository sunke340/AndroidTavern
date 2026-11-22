package app.tavern

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole

/**
 * 聊天页（全屏二级页）。
 *
 * 用 Scaffold + TopAppBar 处理状态栏 insets；`imePadding()` 保证键盘弹出时输入框不被遮。
 * 顶栏右侧「请求」是 M0 留下的自证功能：能看到这次实际发出去的 messages。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val palette = LocalTavernPalette.current

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
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                if (state.transcript.isEmpty() && state.streamingText.isEmpty()) {
                    item { EmptyHint("还没有消息。发一句开始；点右上「请求」可以看到实际发出去的提示词。") }
                }
                items(state.transcript) { message ->
                    MessageRow(message = message, style = state.effectiveMessageStyle)
                }
                if (state.streamingText.isNotEmpty()) {
                    item {
                        MessageRow(
                            message = ChatMessage(ChatRole.Assistant, state.streamingText),
                            style = state.effectiveMessageStyle,
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
                    androidx.compose.foundation.text.BasicTextField(
                        value = state.input,
                        onValueChange = viewModel::onInputChange,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
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

                if (state.isStreaming) {
                    FilledIconButton(
                        onClick = viewModel::stop,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        // 停止用一小块方角，跟"发送"的形状区分开，不依赖图标
                        androidx.compose.foundation.layout.Box(
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

/**
 * 消息渲染的唯一入口：样式切换只在这里分叉。
 * 气泡是 C 方向的默认样式，整行卡片保留为可选项（设置里能切）。
 */
@Composable
private fun MessageRow(message: ChatMessage, style: MessageStyle) {
    when (style) {
        MessageStyle.Bubble -> BubbleRow(message)
        MessageStyle.Card -> CardRow(message)
    }
}

@Composable
private fun BubbleRow(message: ChatMessage) {
    val palette = LocalTavernPalette.current
    val isUser = message.role == ChatRole.User

    Row(modifier = Modifier.fillMaxWidth()) {
        if (isUser) Spacer(Modifier.weight(0.14f))
        androidx.compose.foundation.layout.Box(
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
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = Space.m, vertical = 10.dp),
                )
            }
        }
    }
}

/** 整行卡片样式（保留选项）：信息密度低，适合长段落阅读。 */
@Composable
private fun CardRow(message: ChatMessage) {
    val palette = LocalTavernPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
            Text(message.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
