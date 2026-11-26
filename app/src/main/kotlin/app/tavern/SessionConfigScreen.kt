package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tavern.core.prompt.AuthorNote
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 会话配置（三级页）：**这一条会话**单独用什么。
 *
 * 三级继承：会话（这里）→ 角色卡（详情页「配置」）→ 全局（设置里那几页）。
 * 每行显示"生效值 · 是谁定的"；点进去是**整页选择**（用户 2026-11-25：不要再弹抽屉）。
 * 思考强度在「模型」页顶部，不再单独占一行。
 */
@Composable
fun SessionConfigScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val glass = state.visualEffects.effective() == VisualEffects.Glass

    fun src(sessionSet: Boolean, cardSet: Boolean): String = when {
        sessionSet -> "会话"
        cardSet -> "角色卡"
        else -> "全局"
    }

    val profileName = state.profiles.firstOrNull { it.id == state.effectiveProfileId }?.name ?: "未配置"
    val modelSrc = src(
        sessionSet = state.sessionOverride.profileId != null,
        cardSet = state.cardOverride.profileId != null,
    )
    val books = effectiveBookNames(state)
    val booksSrc = src(
        sessionSet = state.sessionOverride.worldBookNames != null,
        cardSet = state.cardOverride.worldBookNames != null,
    )
    val regex = effectiveRegexNames(state)
    val regexSrc = src(
        sessionSet = state.sessionOverride.regexIds != null,
        cardSet = state.cardOverride.regexIds != null,
    )
    val preset = state.effectivePresetName ?: "内置默认"
    val presetSrc = src(
        sessionSet = state.sessionOverride.presetName != null,
        cardSet = state.cardOverride.presetName != null,
    )
    val bubbleSrc = src(
        sessionSet = state.sessionOverride.bubbleFontSize != null,
        cardSet = state.cardOverride.bubbleFontSize != null,
    )

    GlassDetailScaffold(
        title = "会话配置",
        onBack = viewModel::closeSessionConfig,
        glass = glass,
        headerMode = DetailHeaderMode.Translucent,

    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 让位拆两半：底部留在外面，顶部挪进滚动层 —— 内容才能从页头底下穿过去，
                // 页头才是"半透明"而不是一条纯色（用户 2026-11-25 第 7 条）
                .padding(bottom = padding.calculateBottomPadding())
                .rubberOverscroll(rememberRubberOverscroll())
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding())
                .padding(horizontal = Space.l, vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            // 采样这一层的摘要：显示的是**生效值**，后缀说明是本层覆盖还是跟随（用户 2026-11-25 第 2 条）
            val samplingOverride = state.sessionOverride
            val samplingLocal = listOf(
                samplingOverride.temperature,
                samplingOverride.maxTokens,
                samplingOverride.topP,
                samplingOverride.frequencyPenalty,
                samplingOverride.presencePenalty,
            ).any { it != null }
            var noteEditor by remember { mutableStateOf(false) }
            val note = state.effectiveAuthorNote
            val noteSrc = when {
                state.sessionOverride.authorNote != null -> "会话"
                state.cardOverride.authorNote != null -> "角色卡"
                else -> "未设置"
            }
            val personaStored = state.sessionOverride.personaId
            val personaSrc = when {
                personaStored != null -> "本会话"
                state.cardOverride.personaId != null -> "跟随角色卡"
                else -> "跟随全局"
            }
            val personaName = state.effectivePersona?.name ?: "未设置"
            ListGroup(count = 8) { index, _ ->
                when (index) {
                    0 -> SettingLinkRow("模型", "$profileName · $modelSrc") {
                        viewModel.openConfigPicker(ConfigScope.Session, ConfigKind.Model)
                    }

                    1 -> SettingLinkRow(
                        "采样",
                        "temperature ${state.effectiveTemperature} · " +
                            if (samplingLocal) "本会话" else "跟随上级",
                    ) {
                        viewModel.openConfigPicker(ConfigScope.Session, ConfigKind.Sampling)
                    }

                    2 -> SettingLinkRow("世界书", "参与 ${books.size} 本 · $booksSrc") {
                        viewModel.openConfigPicker(ConfigScope.Session, ConfigKind.WorldBook)
                    }

                    3 -> SettingLinkRow("正则", "参与 ${regex.size} 条 · $regexSrc") {
                        viewModel.openConfigPicker(ConfigScope.Session, ConfigKind.Regex)
                    }

                    4 -> SettingLinkRow("预设", "$preset · $presetSrc") {
                        viewModel.openConfigPicker(ConfigScope.Session, ConfigKind.Preset)
                    }

                    5 -> SettingLinkRow("人设", "$personaName · $personaSrc") {
                        viewModel.openConfigPicker(ConfigScope.Session, ConfigKind.Persona)
                    }

                    6 -> SettingLinkRow(
                        "作者注",
                        if (note == null) "未设置" else {
                            "${note.text.length} 字 · 深度 ${note.depth} · " +
                                "每 ${note.interval} 轮 · $noteSrc"
                        },
                    ) { noteEditor = true }

                    else -> SettingLinkRow(
                        "气泡字号",
                        "${bubbleFontSizeLabel(state.effectiveBubbleFontSize)} · $bubbleSrc",
                    ) {
                        viewModel.openConfigPicker(ConfigScope.Session, ConfigKind.FontSize)
                    }
                }
            }

            if (noteEditor) {
                AuthorNoteDialog(
                    initial = note,
                    onSave = { text, depth, role, interval ->
                        viewModel.setSessionAuthorNote(text, depth, role, interval)
                        noteEditor = false
                    },
                    onClose = { noteEditor = false },
                )
            }
        }
    }
}

/**
 * 作者注编辑层（P1-7）。
 *
 * 四项照原版：正文、深度（后面留几条历史）、角色、间隔（每 N 条用户消息插一次）。
 * 原版还有"参与世界书扫描"这个开关，本项目暂未实现（口径写在 compat-matrix 的 P1-7 里）。
 */
@Composable
private fun AuthorNoteDialog(
    initial: AuthorNote?,
    onSave: (String, Int, String, Int) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf(initial?.text.orEmpty()) }
    var depth by remember { mutableStateOf((initial?.depth ?: 4).toString()) }
    var interval by remember { mutableStateOf((initial?.interval ?: 1).toString()) }
    var role by remember {
        mutableStateOf(
            when (initial?.role) {
                app.tavern.core.model.ChatRole.User -> "User"
                app.tavern.core.model.ChatRole.Assistant -> "Assistant"
                else -> "System"
            },
        )
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Text("作者注", style = MaterialTheme.typography.titleMedium)
                Text(
                    "每 N 条用户消息插进聊天历史一次；深度是「它后面留几条消息」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("正文") },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedTextField(
                        value = depth,
                        onValueChange = { depth = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("深度") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("间隔") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                SegmentedRow(
                    options = listOf("System", "User", "Assistant"),
                    selected = role,
                    label = { it },
                    onSelect = { role = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(
                        onClick = {
                            onSave(
                                text,
                                depth.toIntOrNull()?.coerceIn(0, 100) ?: 4,
                                role,
                                interval.toIntOrNull()?.coerceIn(1, 100) ?: 1,
                            )
                        },
                    ) { Text("保存") }
                    OutlinedButton(onClick = onClose) { Text("取消") }
                }
                Spacer(Modifier.height(Space.s))
                Text(
                    "清空正文 = 这一层没有作者注（回到跟随上级）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
