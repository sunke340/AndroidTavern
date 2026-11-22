package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 设置页：连接、采样、外观、关于。所有改动即时落盘（见 ViewModel 的 onXxxChange）。
 *
 * 页头用共用的 [PageScaffold]（无动作按钮），内容自己滚动——与「会话」「角色卡」同一套留白。
 */
@Composable
fun SettingsScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    PageScaffold(title = "设置") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("连接", style = MaterialTheme.typography.labelMedium)
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
                label = { Text("模型") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("采样", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = state.temperatureText,
                onValueChange = viewModel::onTemperatureChange,
                label = { Text("temperature") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.maxTokensText,
                onValueChange = viewModel::onMaxTokensChange,
                label = { Text("max_tokens（0 = 不限）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("外观", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.messageStyle == MessageStyle.Card,
                    onClick = { viewModel.onMessageStyleChange(MessageStyle.Card) },
                    label = { Text("整行卡片") },
                )
                FilterChip(
                    selected = state.messageStyle == MessageStyle.Bubble,
                    onClick = { viewModel.onMessageStyleChange(MessageStyle.Bubble) },
                    label = { Text("气泡（实验）") },
                )
        }
        if (state.messageStyle == MessageStyle.Bubble) {
            Text(
                "气泡样式还没实现，先按整行卡片显示——这个开关只是把接口留好。",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Text("关于", style = MaterialTheme.typography.labelMedium)
        Text(
            "构建 ${if (BuildConfig.BUILD_ID.startsWith("local-")) "local:" + BuildConfig.BUILD_ID.takeLast(7) else BuildConfig.BUILD_ID.take(7)}" +
                "｜版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
            style = MaterialTheme.typography.labelSmall,
        )
        }
    }
}
