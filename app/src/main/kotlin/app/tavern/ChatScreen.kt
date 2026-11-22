package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole

/**
 * 聊天页（全屏二级页）。
 *
 * 用 `Scaffold` + `TopAppBar` 而不是裸 `Column`：这样状态栏高度由 Scaffold/TopAppBar
 * 的 windowInsets 自己吃掉，内容不会被状态栏压住（之前的 bug）；
 * 再加 `imePadding()`，键盘弹出时输入框不会被挡住。
 *
 * 顶栏右侧「请求」是 M0 留下来的自证功能：能把这次实际发出去的 messages 原样打出来，
 * 排查"卡片有没有注入"时最有用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // IconButton 自带 48dp 触控区；字号调大，别让人戳不准
                    IconButton(onClick = viewModel::closeChat) {
                        Text("←", fontSize = 28.sp)
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.showRequest) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
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
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.transcript.isEmpty() && state.streamingText.isEmpty()) {
                    item {
                        Text(
                            "还没有消息。发一句开始；也可以点右上「请求」看实际发出去的提示词。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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

            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                label = { Text("说点什么") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::send,
                    enabled = !state.isStreaming,
                    modifier = Modifier.weight(1f),
                ) { Text("发送") }
                OutlinedButton(
                    onClick = viewModel::stop,
                    enabled = state.isStreaming,
                ) { Text("停止") }
            }
        }
    }
}

/**
 * 消息渲染的唯一入口。样式切换只在这里分叉（见 [MessageStyle]）：
 * 加气泡时改这个 `when` 就够了，聊天逻辑/数据库/提示词都不用动。
 */
@Composable
private fun MessageRow(message: ChatMessage, style: MessageStyle) {
    when (style) {
        MessageStyle.Card -> CardRow(message)
        // 气泡还没实现：先按卡片显示，避免设置里选了却是一片空白
        MessageStyle.Bubble -> CardRow(message)
    }
}

@Composable
private fun CardRow(message: ChatMessage) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = if (message.role == ChatRole.User) "我" else "角色",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(message.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
