package app.tavern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 设置的二级页面。
 *
 * 统一骨架：顶栏（返回 + 标题）+ 内容区左右 16dp。所有选项控件等宽铺满，
 * 不再出现"chip 宽度跟着文字走"的参差感。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSubPage(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val page = state.settingsPage ?: return

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = viewModel::closeSettingsPage) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(page.title, style = MaterialTheme.typography.titleMedium)
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            when (page) {
                SettingsPage.Model -> ModelPage(state, viewModel)
                SettingsPage.Sampling -> SamplingPage(state, viewModel)
                SettingsPage.Appearance -> AppearancePage(state, viewModel)
                SettingsPage.WorldBook -> WorldBookPage(state, viewModel)
                SettingsPage.About -> AboutPage(state)
            }
            Box(modifier = Modifier.size(1.dp).padding(bottom = Space.xl))
        }
    }
}

// ---- 模型 -----------------------------------------------------------------

@Composable
private fun ModelPage(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    SectionLabel("当前模型")
    Text(
        "多模型档案（列出、切换默认、每模型独立 Key/请求头）在下一批。现在这里是唯一的一套连接配置。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = state.model,
        onValueChange = viewModel::onModelChange,
        label = { Text("模型 ID") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
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
        label = { Text("API Key（Keystore 加密保存）") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---- 采样 -----------------------------------------------------------------

@Composable
private fun SamplingPage(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    SectionLabel("采样参数")
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
    Text(
        "留空或解析不了的数字不会保存，避免把半截输入写进配置。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---- 外观 -----------------------------------------------------------------

@Composable
private fun AppearancePage(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    SectionLabel("主题色")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        ThemePreset.entries.forEach { preset ->
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ThemeSwatch(
                    preset = preset,
                    selected = preset == state.themePreset,
                    onClick = { viewModel.setThemePreset(preset) },
                )
            }
        }
    }
    Text(
        state.themePreset.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SectionLabel("深浅")
    SegmentedRow(
        options = ThemeMode.entries,
        selected = state.themeMode,
        label = { it.label },
        onSelect = viewModel::setThemeMode,
    )

    SectionLabel("对话样式")
    SegmentedRow(
        options = listOf(MessageStyle.Bubble, MessageStyle.Card),
        selected = state.messageStyle,
        label = { if (it == MessageStyle.Bubble) "气泡" else "整行卡片" },
        onSelect = viewModel::onMessageStyleChange,
    )
    Text(
        "整行卡片信息密度更低，适合长段落；角色详情里可以按卡单独覆盖。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 主题色圆点：用浅色版 primary 作为识别色，选中加一圈描边。 */
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
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline,
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

// ---- 世界书 ---------------------------------------------------------------

/**
 * 只讲**全局世界书**。
 *
 * 卡内嵌世界书属于那张卡，勾选入口只在角色详情 → 配置里；
 * 在这里再列一遍会让同一个东西有两个入口、语义还不一样。
 */
@Composable
private fun WorldBookPage(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    SectionLabel("全局世界书")
    Text(
        "跨角色共用的世界书。世界书引擎（条目激活、递归、预算）还没实现，所以现在还不能导入。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    EmptyHint("还没有全局世界书。等世界书引擎（P1）实现后，这里会支持导入 ST 的世界书 JSON 并绑定到会话。")

    SectionLabel("角色卡自带的世界书呢？")
    Text(
        "它属于那张卡，入口在：角色详情 → 配置 → 内嵌世界书。不在设置里重复出现。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---- 关于 -----------------------------------------------------------------

@Composable
private fun AboutPage(state: ChatViewModel.UiState) {
    SectionLabel("构建")
    Text(
        "构建 ${if (BuildConfig.BUILD_ID.startsWith("local-")) "local:" + BuildConfig.BUILD_ID.takeLast(7) else BuildConfig.BUILD_ID.take(7)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
        style = MaterialTheme.typography.bodyMedium,
    )

    SectionLabel("说明")
    Text(
        "原生安卓酒馆：兼容 SillyTavern 的文件格式与提示词行为，单进程、无服务端、无 WebView。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "当前状态见仓库 docs/compat-matrix.md。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
