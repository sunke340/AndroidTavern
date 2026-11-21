package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("AndroidTavern M0", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = viewModel::toggleSettings) {
                Text(if (state.showSettings) "收起设置" else "设置")
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
                    label = { Text("API Key（只存在内存里）") },
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
            }
        }

        Text(state.status, style = MaterialTheme.typography.labelSmall)

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
