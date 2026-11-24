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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import app.tavern.core.model.ModelProfile
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

    GlassDetailScaffold(
        title = page.title,
        onBack = viewModel::closeSettingsPage,
        glass = state.visualEffects.effective() == VisualEffects.Glass,
        actions = if (page == SettingsPage.Model) {
            { CapsuleIcon(icon = Icons.Filled.Add, contentDescription = "新建档案") { viewModel.openProfileEditor(null) } }
        } else {
            null
        },
    ) { padding ->
        val overscroll = rememberRubberOverscroll()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .rubberOverscroll(overscroll)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            when (page) {
                SettingsPage.Model -> ModelPage(state, viewModel)
                SettingsPage.Sampling -> SamplingPage(state, viewModel)
                SettingsPage.Appearance -> AppearancePage(state, viewModel)
                SettingsPage.WorldBook -> WorldBookPage(state, viewModel)
                SettingsPage.Regex -> RegexPage(state, viewModel)
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
 * **整行点按＝切换到这个档案**（主操作），**长按＝抽屉**（编辑 / 删除，次操作）。
 * 之前是反的：点整行进编辑，「设为默认」是行尾一个 11sp 小字按钮（触控区约 30dp）
 * 紧挨着整行的点击区 —— 用户每天做的是换模型，却要去点最不容易点中的那个。
 *
 * 副行只留模型 ID（+ 思考档）；baseUrl 是编辑页的事，手机上一行放不下。
 */
@Composable
private fun ModelPage(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    var pendingDelete by remember { mutableStateOf<ModelProfile?>(null) }
    val sheet = LocalSheetController.current

    if (state.profiles.isEmpty()) {
        EmptyHint("还没有档案。点右上「新建」。")
        return
    }

    ListGroup(
        count = state.profiles.size,
        // 列表直接顶着页头会显得挤（其它设置二级页开头有 SectionLabel 自带 16dp）
        modifier = Modifier.padding(top = Space.l),
        // 分隔线让出"行内边距 + 圆圈 + 间距"，从标题左缘起
        dividerInset = RowToken.padH + 20.dp + Space.m,
    ) { index, _ ->
        val profile = state.profiles[index]
        ListRow(
            title = profile.name,
            subtitle = buildString {
                append(profile.model)
                profile.reasoningEffort?.takeIf { it.isNotBlank() }?.let {
                    append(" · 思考 ").append(it)
                }
            },
            leading = { ActiveMark(profile.isDefault) },
            onClick = { viewModel.setDefaultProfile(profile.id) },
            onLongClick = {
                sheet.show(
                    title = profile.name,
                    actions = listOf(
                        SheetAction("编辑") { viewModel.openProfileEditor(profile.id) },
                        SheetAction("删除", destructive = true) { pendingDelete = profile },
                    ),
                )
            },
        )
    }

    pendingDelete?.let { profile ->
        ConfirmDeleteDialog(
            title = "删除档案？",
            message = "「${profile.name}」和它保存的 API Key 会一起删掉，不能撤销。",
            onConfirm = {
                pendingDelete = null
                viewModel.deleteProfile(profile.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** 当前使用的档案：实心勾；其余是空心圈。点整行就切换，这里只负责"看得见"。 */
@Composable
private fun ActiveMark(isActive: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .then(
                if (isActive) {
                    Modifier.background(primary)
                } else {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isActive) {
            Text(
                "✓",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
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

    // 玻璃档要 API 33+（AGSL 液态玻璃）；不支持就只留轻量，不给选
    if (glassSupported) {
        SectionLabel("视觉效果")
        SegmentedRow(
            options = VisualEffects.entries,
            selected = state.visualEffects,
            label = { it.label },
            onSelect = viewModel::setVisualEffects,
        )
    }

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
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importWorldBook(uri)
    }
    // 删除走长按：行尾只留开关，不再塞一个删除图标（那会让每行都很挤，也容易误触）
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val sheet = LocalSheetController.current

    SectionLabel("全局世界书（${state.worldBooks.size} 本）")
    if (state.worldBooks.isEmpty()) {
        EmptyHint("还没有全局世界书。导入 PC 版的世界书 .json，打开开关后它对所有会话生效。")
    } else {
        ListGroup(count = state.worldBooks.size) { index, _ ->
            val book = state.worldBooks[index]
            ListRow(
                title = book.name,
                // 开关已经表达启用态，副行只在停用时补一句，扫列表时不用再读开关
                subtitle = if (book.enabled) {
                    "${book.entryCount} 条"
                } else {
                    "${book.entryCount} 条 · 已停用"
                },
                trailing = {
                    Switch(
                        checked = book.enabled,
                        onCheckedChange = { viewModel.setWorldBookEnabled(book.name, it) },
                    )
                },
                onClick = { viewModel.openWorldBook(book.name) },
                onLongClick = {
                    sheet.show(
                        title = book.name,
                        actions = listOf(
                            SheetAction("删除世界书", destructive = true) {
                                pendingDelete = book.name
                            },
                        ),
                    )
                },
            )
        }
    }

    Spacer(Modifier.height(Space.m))
    OutlinedButton(
        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("导入世界书（.json）")
    }
    pendingDelete?.let { name ->
        ConfirmDeleteDialog(
            title = "删除世界书？",
            message = "「$name」会从这台设备删掉，之后可以重新导入。",
            onConfirm = {
                viewModel.deleteWorldBook(name)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

// ---- 正则 ---------------------------------------------------------------

/**
 * 正则脚本。作用位置（placement）沿用原版数字：1=用户输入、2=模型输出、5=世界书。
 *
 * 这里只做"导入 / 开关"：脚本是文件，编辑仍在 PC 版做——手机上写正则是最难受的事。
 */
@Composable
private fun RegexPage(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importRegexScripts(uri)
    }

    SectionLabel("正则脚本（${state.regexScripts.size} 条）")
    if (state.regexScripts.isEmpty()) {
        EmptyHint("还没有正则脚本。可以导入 ST 导出的脚本 JSON，或 settings.json 里的 regex 数组。")
    } else {
        ListGroup(count = state.regexScripts.size) { index, _ ->
            val script = state.regexScripts[index]
            ListRow(
                title = script.name.ifBlank { script.findRegex },
                subtitle = placementLabel(script) + " · " +
                    (if (script.disabled) "已停用" else "已启用"),
                trailing = {
                    Switch(
                        checked = !script.disabled,
                        onCheckedChange = { enabled ->
                            viewModel.setRegexScriptDisabled(script.name, !enabled)
                        },
                    )
                },
            )
        }
    }

    Spacer(Modifier.height(Space.m))
    OutlinedButton(
        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("导入正则脚本（.json）")
    }
}

private fun placementLabel(script: app.tavern.core.regex.RegexScript): String = when {
    script.placement.isEmpty() -> "未指定位置"
    else -> script.placement.sortedBy { it.value }.joinToString("+") { placement ->
        when (placement) {
            app.tavern.core.regex.RegexPlacement.UserInput -> "用户输入"
            app.tavern.core.regex.RegexPlacement.AiOutput -> "模型输出"
            app.tavern.core.regex.RegexPlacement.WorldInfo -> "世界书"
            app.tavern.core.regex.RegexPlacement.Reasoning -> "推理"
            app.tavern.core.regex.RegexPlacement.SlashCommand -> "命令"
            app.tavern.core.regex.RegexPlacement.MarkdownDisplay -> "Markdown"
        }
    }
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

    // 只有 debug 包里有这一块：材质样张 + 参数滑杆。发布包里连入口都不存在。
    if (BuildConfig.DEBUG) {
        SectionLabel("开发")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedButton(onClick = viewModel::openGallery) { Text("组件预览") }
        }
    }
}
