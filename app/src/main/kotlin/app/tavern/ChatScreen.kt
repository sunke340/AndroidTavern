package app.tavern

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole

@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // SAF：只挑图片，但真正的判定在 CardStore（读得出 chara/ccv3 才算角色卡）
    val pickCard = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importCard(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("AndroidTavern M1", style = MaterialTheme.typography.titleMedium)
                // 带 build id：出问题时能一眼确认手机上装的是不是最新那个包
                Text(
                    if (BuildConfig.BUILD_ID.startsWith("local-")) {
                        "local:" + BuildConfig.BUILD_ID.takeLast(7)
                    } else {
                        BuildConfig.BUILD_ID.take(7)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row {
                TextButton(onClick = viewModel::toggleSessions) { Text("会话") }
                TextButton(onClick = viewModel::toggleRequest) { Text("请求") }
                TextButton(onClick = viewModel::toggleSettings) {
                    Text(if (state.showSettings) "收设置" else "设置")
                }
            }
        }

        if (state.showSessions) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("会话（点一行就切过去）", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = viewModel::newSession) { Text("新建") }
                    }
                    if (state.sessions.isEmpty()) {
                        Text("还没有会话", style = MaterialTheme.typography.bodySmall)
                    }
                    state.sessions.forEach { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.openSession(session.id) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (state.sessionId == session.id) "●" else "○",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(session.title, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${session.cardName}｜${session.messageCount} 条",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            TextButton(onClick = { viewModel.deleteSession(session.id) }) { Text("删") }
                        }
                    }
                }
            }
        }

        if (state.showSettings) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::onBaseUrlChange,
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::onApiKeyChange,
                    label = { Text("API Key（加密保存，重开还在）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.model,
                    onValueChange = viewModel::onModelChange,
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.width(4.dp))
                Text("卡片（点一下切换）", style = MaterialTheme.typography.labelMedium)
                state.cards.forEach { ref ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectCard(ref.id) }
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.cardId == ref.id,
                            onClick = { viewModel.selectCard(ref.id) },
                        )
                        Text(
                            text = ref.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (ref.imported) {
                            Spacer(Modifier.width(6.dp))
                            Text("导入", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                OutlinedButton(
                    onClick = { pickCard.launch(arrayOf("image/*")) },
                    enabled = !state.importing,
                ) {
                    Text(if (state.importing) "导入中…" else "导入 PNG 角色卡")
                }
            }
        }

        Text(state.status, style = MaterialTheme.typography.labelSmall)

        if (state.showRequest) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
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
            items(state.transcript) { message -> MessageBubble(message) }
            if (state.streamingText.isNotEmpty()) {
                item { MessageBubble(ChatMessage(ChatRole.Assistant, state.streamingText)) }
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

@Composable
private fun MessageBubble(message: ChatMessage) {
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
