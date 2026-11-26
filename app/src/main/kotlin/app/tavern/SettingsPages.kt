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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import app.tavern.core.regex.BuiltinRegex
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

    // 「导入」统一放右上角胶囊（和模型页「新建」同一个位置）：列表本身就占整页，
    // 底部再挂一条通栏按钮会把页面又切一刀 —— 预设编辑页是同一个理由。
    val jsonMime = arrayOf("application/json", "*/*")
    val worldBookImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importWorldBook(uri)
    }
    val regexImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importRegexScripts(uri)
    }
    val presetImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPreset(uri)
    }

    GlassDetailScaffold(
        title = page.title,
        onBack = viewModel::closeSettingsPage,
        glass = state.visualEffects.effective() == VisualEffects.Glass,
        headerMode = DetailHeaderMode.Translucent,

        actions = when (page) {
            SettingsPage.Model -> {
                { CapsuleIcon(icon = Icons.Filled.Add, contentDescription = "新建档案") { viewModel.openProfileEditor(null) } }
            }

            SettingsPage.WorldBook -> {
                { CapsuleIcon(icon = Icons.Filled.Add, contentDescription = "导入世界书") { worldBookImport.launch(jsonMime) } }
            }

            SettingsPage.Regex -> {
                { CapsuleIcon(icon = Icons.Filled.Add, contentDescription = "导入正则脚本") { regexImport.launch(jsonMime) } }
            }

            SettingsPage.Preset -> {
                { CapsuleIcon(icon = Icons.Filled.Add, contentDescription = "导入预设") { presetImport.launch(jsonMime) } }
            }

            else -> null
        },
    ) { padding ->
        val overscroll = rememberRubberOverscroll()
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 让位拆两半：底部留在外面，顶部挪进滚动层 —— 内容才能从页头底下穿过去，
                // 页头才是"半透明"而不是一条纯色（用户 2026-11-25 第 7 条）
                .padding(bottom = padding.calculateBottomPadding())
                .rubberOverscroll(overscroll)
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding())
                .padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            when (page) {
                SettingsPage.Model -> ModelPage(state, viewModel)
                SettingsPage.Sampling -> SamplingPage(state, viewModel)
                SettingsPage.Preset -> PresetPage(state, viewModel)
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
            subtitle = profile.model,
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
internal fun ActiveMark(isActive: Boolean) {
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
    // 以下三项留空 = 不指定（请求里就不发，交服务端默认）。用户 2026-11-25 第 2 条把采样补成五项。
    OutlinedTextField(
        value = state.topPText,
        onValueChange = viewModel::onTopPChange,
        label = { Text("top_p（留空 = 不指定）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.frequencyPenaltyText,
        onValueChange = viewModel::onFrequencyPenaltyChange,
        label = { Text("frequency_penalty（留空 = 不指定）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.presencePenaltyText,
        onValueChange = viewModel::onPresencePenaltyChange,
        label = { Text("presence_penalty（留空 = 不指定）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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

    // 气泡字号（用户 2026-11-26 第 6 条）：只有聊天页气泡正文受影响，
    // 会话 / 角色卡还能各自覆盖（三级继承，入口在各自的配置页）。
    SectionLabel("气泡字号")
    SegmentedRow(
        options = BubbleFontSizes,
        selected = state.bubbleFontSize,
        label = { bubbleFontSizeLabel(it) },
        onSelect = viewModel::setBubbleFontSize,
    )

    // 揭示节奏（2026-11-27，plan-reveal-pacing）：回复"逐字打出来"的速度。
    // 单位是码点/秒；不限速 = 收到就显示（这个方案之前的行为）。程序不会自己加速，
    // 也不设最长打印时间 —— 觉得慢就换档，逃生口是点一下正在打印的气泡。
    SectionLabel("打印节奏")
    SegmentedRow(
        options = RevealSpeed.entries,
        selected = state.revealSpeed,
        label = { it.label },
        onSelect = viewModel::onRevealSpeedChange,
    )
    Text(
        state.revealSpeed.summary,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    // 删除走长按：行尾只留开关，不再塞一个删除图标（那会让每行都很挤，也容易误触）
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val sheet = LocalSheetController.current

    if (state.worldBooks.isEmpty()) {
        EmptyHint("还没有全局世界书。点右上「+」导入 PC 版的世界书 .json，打开开关后它对所有会话生效。")
    } else {
        ListGroup(
            count = state.worldBooks.size,
            // 页头下面直接顶列表会显得挤，留一点呼吸（和模型页一致）
            modifier = Modifier.padding(top = Space.l),
        ) { index, _ ->
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
    // 内置显示规则（用户 2026-11-25 定的形态）：只读、带开关、关掉就是原文。
    // 它们不写进用户脚本，也不随导入覆盖；只影响气泡显示。
    SectionLabel("内置")
    ListGroup(
        count = BuiltinRegex.features.size,
        modifier = Modifier.padding(top = Space.s),
    ) { index, _ ->
        val feature = BuiltinRegex.features[index]
        ListRow(
            title = feature.title,
            subtitle = feature.description,
            subtitleMaxLines = 2,
            trailing = {
                Switch(
                    checked = feature.id !in state.builtinRegexDisabled,
                    onCheckedChange = { enabled ->
                        viewModel.setBuiltinRegexEnabled(feature.id, enabled)
                    },
                )
            },
        )
    }

    SectionLabel("导入的脚本")
    if (state.regexScripts.isEmpty()) {
        EmptyHint("还没有正则脚本。点右上「+」导入 ST 导出的脚本 JSON，或 settings.json 里的 regex 数组。")
    } else {
        ListGroup(
            count = state.regexScripts.size,
            modifier = Modifier.padding(top = Space.l),
        ) { index, _ ->
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

    // 开源许可（审计 P2）：把构建期同步进 `assets/notices/` 的文本**读出来**。
    // 按实际存在的文件列，不硬编码清单 —— 以后加一份许可不用改代码。
    // 声明不能只躺在 APK 里：用户要能在设置里看到它。
    val notices = remember { noticeFiles(context) }
    var reading by remember { mutableStateOf<NoticeFile?>(null) }
    if (notices.isNotEmpty()) {
        SectionLabel("开源许可")
        ListGroup(count = notices.size) { index, _ ->
            val file = notices[index]
            SettingLinkRow(file.title, file.sizeLabel) { reading = file }
        }
    }
    reading?.let { file -> NoticeDialog(file = file, onClose = { reading = null }) }

    // 只有 debug 包里有这一块：材质样张 + 参数滑杆。发布包里连入口都不存在。
    if (BuildConfig.DEBUG) {
        SectionLabel("开发")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedButton(onClick = viewModel::openGallery) { Text("组件预览") }
        }
    }
}

// ---- 预设（P1-5） ---------------------------------------------------------

/**
 * 预设页：选哪一份 + 导入。
 *
 * 顺序的开关与拖动在**编辑页**里做（长按这一行 → 「编辑」）：那一页要装一张可拖动的列表，
 * 和"选预设"这种一眼扫过去的列表混在一起会让两件事都变难。
 */
@Composable
private fun PresetPage(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val sheet = LocalSheetController.current

    // 勾选放左侧（和模型页一致）：分隔线要让出"行内边距 + 圆圈 + 间距"，从标题左缘起
    ListGroup(
        count = state.presets.size + 1,
        modifier = Modifier.padding(top = Space.l),
        dividerInset = RowToken.padH + 20.dp + Space.m,
    ) { index, _ ->
        if (index == 0) {
            PresetRow(
                name = "内置默认预设",
                subtitle = "原版 Default.json 那张表",
                active = state.activePreset == null,
                onClick = { viewModel.selectPreset(null) },
            )
        } else {
            val preset = state.presets[index - 1]
            PresetRow(
                name = preset.name,
                subtitle = if (preset.disabledCount == 0) {
                    "${preset.promptCount} 段提示词"
                } else {
                    "${preset.promptCount} 段 · 关 ${preset.disabledCount}"
                },
                active = state.activePreset == preset.name,
                onClick = { viewModel.selectPreset(preset.name) },
                onLongClick = {
                    sheet.show(
                        title = preset.name,
                        actions = listOf(
                            SheetAction("编辑") { viewModel.openPresetEditor(preset.name) },
                            SheetAction("删除预设", destructive = true) {
                                pendingDelete = preset.name
                            },
                        ),
                    )
                },
            )
        }
    }

    if (state.activePreset == null) {
        Spacer(Modifier.height(Space.m))
        EmptyHint("内置默认预设的顺序不能改。导入一份预设后，长按它可以进「编辑」开关与拖动顺序。")
    }

    pendingDelete?.let { name ->
        ConfirmDeleteDialog(
            title = "删除预设？",
            message = "「$name」会从这台设备删掉，之后可以重新导入。",
            onConfirm = {
                viewModel.deletePreset(name)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun PresetRow(
    name: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    ListRow(
        title = name,
        subtitle = subtitle,
        // 勾选在左侧、和模型页是同一个 ActiveMark：实心圈 + 勾 = 当前，空心圈 = 未选中
        leading = { ActiveMark(active) },
        onClick = onClick,
        onLongClick = onLongClick,
    )
}


// ---- 开源许可 -------------------------------------------------------------

/** `assets/notices/` 里的一份文本：标题给人看，[assetPath] 是资产路径。 */
private data class NoticeFile(val title: String, val assetPath: String, val sizeLabel: String)

/**
 * 构建期 `syncNotices` 会把根目录的 `LICENSE` / `THIRD_PARTY_NOTICES.md` / `licenses/`
 * 拷进 `assets/notices/`（映射的是**目录内容**，所以路径要多一层）。这里按实际存在的文件列，
 * 不硬编码清单；读不出来的（目录名之类）自动跳过。
 */
private fun noticeFiles(context: Context): List<NoticeFile> {
    val assets = context.assets
    fun sizeLabel(path: String): String {
        val bytes = runCatching { assets.open(path).use { it.available() } }.getOrDefault(0)
        return if (bytes >= 1024) "${bytes / 1024} KB" else "$bytes B"
    }
    fun titleOf(name: String): String = when {
        name == "THIRD_PARTY_NOTICES.md" -> "第三方声明"
        name == "LICENSE.txt" -> "GNU AGPL-3.0（本项目）"
        name == "Apache-2.0.txt" -> "Apache License 2.0"
        name.startsWith("MIT-shuding") -> "MIT · shuding/liquid-glass"
        name.startsWith("MIT-emilkowalski") -> "MIT · emilkowalski/skills"
        else -> name
    }

    val out = mutableListOf<NoticeFile>()
    val top = runCatching { assets.list("notices")?.toList().orEmpty() }.getOrDefault(emptyList())
    // 先声明、再本项目协议，最后才是别的
    val ordered = listOf("THIRD_PARTY_NOTICES.md", "LICENSE.txt") +
        top.filter { it != "THIRD_PARTY_NOTICES.md" && it != "LICENSE.txt" }.sorted()
    ordered.forEach { name ->
        val path = "notices/$name"
        val readable = runCatching { assets.open(path).use { it.available() } }.isSuccess
        if (readable) out += NoticeFile(titleOf(name), path, sizeLabel(path))
    }
    val sub = runCatching { assets.list("notices/licenses")?.toList().orEmpty() }
        .getOrDefault(emptyList())
        .sorted()
    sub.forEach { name ->
        val path = "notices/licenses/$name"
        val readable = runCatching { assets.open(path).use { it.available() } }.isSuccess
        if (readable) out += NoticeFile(titleOf(name), path, sizeLabel(path))
    }
    return out
}

/**
 * 许可全文的阅读层：整屏 + 可滚 + 可选字 + 复制（和「查看请求」同一套做法）。
 *
 * 文本在 IO 线程读：AGPL 全文 34KB，不能在组合里同步读。
 */
@Composable
private fun NoticeDialog(file: NoticeFile, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var body by remember(file.assetPath) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.assetPath) {
        body = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(file.assetPath).bufferedReader().use { it.readText() }
            }.getOrElse { "（读不出来：${it.message}）" }
        }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.s, vertical = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(start = Space.s)) {
                        Text(file.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "随应用分发 · ${file.sizeLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(body.orEmpty())) }) {
                        Text("复制")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                HorizontalDivider(color = LocalTavernPalette.current.divider)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .rubberOverscroll(rememberRubberOverscroll())
                        .verticalScroll(rememberScrollState())
                        .padding(Space.l),
                ) {
                    val text = body
                    if (text == null) {
                        Text("读取中…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        SelectionContainer {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
