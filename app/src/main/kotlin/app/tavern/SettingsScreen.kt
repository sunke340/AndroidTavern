package app.tavern

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 设置页：连接 / 采样 / 主题 / 外观 / 关于。所有改动即时落盘。
 *
 * 主题放在这里：预设配色 + 深浅模式，两者独立保存（换深浅不会丢掉选中的配色）。
 */
@Composable
fun SettingsScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    PageScaffold(title = "设置") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            SectionLabel("连接")
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

            SectionLabel("采样")
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

            SectionLabel("主题")
            Text(
                "配色：${state.themePreset.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                ThemePreset.entries.forEach { preset ->
                    ThemeSwatch(
                        preset = preset,
                        selected = preset == state.themePreset,
                        onClick = { viewModel.setThemePreset(preset) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = mode == state.themeMode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(mode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }

            SectionLabel("外观")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FilterChip(
                    selected = state.messageStyle == MessageStyle.Bubble,
                    onClick = { viewModel.onMessageStyleChange(MessageStyle.Bubble) },
                    label = { Text("气泡") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                FilterChip(
                    selected = state.messageStyle == MessageStyle.Card,
                    onClick = { viewModel.onMessageStyleChange(MessageStyle.Card) },
                    label = { Text("整行卡片") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }

            SectionLabel("关于")
            Text(
                "构建 ${if (BuildConfig.BUILD_ID.startsWith("local-")) "local:" + BuildConfig.BUILD_ID.takeLast(7) else BuildConfig.BUILD_ID.take(7)}" +
                    "｜版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("", modifier = Modifier.padding(bottom = Space.l))
        }
    }
}

/** 主题色圆点：用浅色版的 primary 作为识别色，选中时加一圈描边。 */
@Composable
private fun ThemeSwatch(preset: ThemePreset, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(preset.light.primary)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text(
                    "✓",
                    color = preset.light.onPrimary,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}
