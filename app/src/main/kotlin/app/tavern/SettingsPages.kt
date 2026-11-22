package app.tavern

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.widget.Toast
import app.tavern.data.log.AppLog

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
                actions = {
                    if (page == SettingsPage.Model) {
                        PrimaryAction(
                            text = "＋ 新建",
                            onClick = { viewModel.openProfileEditor(null) },
                        )
                    }
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
                SettingsPage.About -> AboutPage(state, viewModel)
            }
            Box(modifier = Modifier.size(1.dp).padding(bottom = Space.xl))
        }
    }
}

// ---- 模型 ---------------------------------------------------------------

/**
 * 模型档案列表。
 *
 * 一级的「模型」行只显示"当前用哪个"；这里才是可以被增删的清单，
 * 点一行进编辑页（三级，`Destination.ProfileEdit`）。
 */
@Composable
private fun ModelPage(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    state.profiles.forEach { profile ->
        ListRow(
            title = profile.name,
            subtitle = buildString {
                append(profile.model)
                profile.reasoningEffort?.let { append(" · ").append(it) }
                append("\n").append(profile.baseUrl)
            },
            trailing = {
                DefaultSlot(
                    isDefault = profile.isDefault,
                    onSetDefault = { viewModel.setDefaultProfile(profile.id) },
                )
            },
            onClick = { viewModel.openProfileEditor(profile.id) },
        )
    }
    if (state.profiles.isEmpty()) EmptyHint("还没有档案。点右上「新建」。")
}

/**
 * 「默认」只有一个呈现位置：行尾。
 *
 * 默认 → 淡紫徽标；非默认 → 同一位置的文字按钮。之前把「· 默认」拼在标题后面、
 * 动作又是一个按钮，会被读成两件不相干的事（用户 2026-11-22 提）。
 */
@Composable
private fun DefaultSlot(isDefault: Boolean, onSetDefault: () -> Unit) {
    if (isDefault) {
        Text(
            "默认",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.button))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                .padding(horizontal = Space.s, vertical = 2.dp),
        )
    } else {
        Text(
            "设为默认",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onSetDefault)
                .padding(vertical = Space.xs),
        )
    }
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

    SectionLabel("视觉效果")
    SegmentedRow(
        options = VisualEffects.entries,
        selected = state.visualEffects,
        label = { it.label },
        onSelect = viewModel::setVisualEffects,
    )

    SectionLabel("页面转场")
    SegmentedRow(
        options = NavAnimation.entries,
        selected = state.navAnimation,
        label = { it.label },
        onSelect = viewModel::setNavAnimation,
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
    EmptyHint("还没有全局世界书。")
}

// ---- 关于 -----------------------------------------------------------------

/**
 * 关于 + 诊断。
 *
 * 「导出日志」是给真机排障用的：应用内日志只记事件与状态码，不含 Key 与消息正文；
 * 导出走 SAF，落到用户自己选的位置，不需要存储权限。
 */
@Composable
private fun AboutPage(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val context = LocalContext.current
    val buildLabel = if (BuildConfig.BUILD_ID.startsWith("local-")) {
        "local:" + BuildConfig.BUILD_ID.takeLast(7)
    } else {
        BuildConfig.BUILD_ID.take(7)
    }

    var logCount by remember { mutableIntStateOf(AppLog.count()) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = AppLog.exportText("$buildLabel ${BuildConfig.VERSION_NAME}")
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("打不开输出流")
        }.isSuccess
        AppLog.i("diag", "导出日志 ok=$ok chars=${text.length}")
        Toast.makeText(
            context,
            if (ok) "日志已导出（${text.length} 字符）" else "导出失败，换个位置再试",
            Toast.LENGTH_SHORT,
        ).show()
    }

    SectionLabel("构建")
    Text("构建 $buildLabel", style = MaterialTheme.typography.bodyMedium)
    Text(
        "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
        style = MaterialTheme.typography.bodyMedium,
    )

    SectionLabel("诊断")
    Text(
        "日志不含 API Key 与消息正文",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "缓存 $logCount 条",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Button(
            onClick = {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                exportLauncher.launch("tavern-log-$stamp.txt")
            },
        ) { Text("导出日志") }
        OutlinedButton(
            onClick = {
                AppLog.clear()
                logCount = AppLog.count()
                Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
            },
        ) { Text("清空") }
    }

}
