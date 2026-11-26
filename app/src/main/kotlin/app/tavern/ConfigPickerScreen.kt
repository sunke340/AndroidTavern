package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.tavern.core.regex.BuiltinRegex

/**
 * 三级配置的「选一项」页：模型 / 世界书 / 正则 / 预设。
 *
 * 用户 2026-11-25：这些行不该弹底部抽屉，应该像设置页的二级页那样**整页跳进去选**
 * （参考卡详情 → 配置 →「对话样式」那一页的观感）。聊天页左下角的快速换模型抽屉是唯一保留的。
 *
 * 选完**不自动返回**（尤其世界书/正则是多选）：返回键或左上角返回钮才离开，
 * 这样能连着对比几个选项。当前生效项用和设置页同一种勾选圈标出来。
 */
@Composable
fun ConfigPickerScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val target = state.configPicker ?: return
    val glass = state.visualEffects.effective() == VisualEffects.Glass

    // 作用域是"值"，不是说明文案：这一页改的是会话还是这张卡。
    // 世界书/正则这两个多选页把「跟随上级」也挪进了右上菜单（用户 2026-11-25 第 4/5 条），
    // 所以副标题要顺带说明现在是不是跟随态 —— 否则列表里看不出"我有没有覆盖"。
    val scopeLabel = if (target.scope == ConfigScope.Session) "本会话" else "这张卡"
    val following = when (target.kind) {
        ConfigKind.WorldBook -> when (target.scope) {
            ConfigScope.Session -> state.sessionOverride.worldBookNames
            ConfigScope.Card -> state.cardOverride.worldBookNames
        }.let { it == null }

        ConfigKind.Regex -> when (target.scope) {
            ConfigScope.Session -> state.sessionOverride.regexIds
            ConfigScope.Card -> state.cardOverride.regexIds
        }.let { it == null }

        ConfigKind.FontSize -> when (target.scope) {
            ConfigScope.Session -> state.sessionOverride.bubbleFontSize
            ConfigScope.Card -> state.cardOverride.bubbleFontSize
        }.let { it == null }

        ConfigKind.Persona -> when (target.scope) {
            ConfigScope.Session -> state.sessionOverride.personaId
            ConfigScope.Card -> state.cardOverride.personaId
        }.let { it == null }

        else -> false
    }
    // 这三类都有"跟随上级"这一档；模型/预设/采样在页面里各自表达，副标题只写作用域
    val followable = target.kind == ConfigKind.WorldBook ||
        target.kind == ConfigKind.Regex ||
        target.kind == ConfigKind.FontSize ||
        target.kind == ConfigKind.Persona
    val subtitle = if (followable) {
        if (following) "$scopeLabel · 跟随上级" else scopeLabel
    } else {
        scopeLabel
    }

    GlassDetailScaffold(
        title = target.kind.title,
        subtitle = subtitle,
        onBack = viewModel::closeConfigPicker,
        glass = glass,
        headerMode = DetailHeaderMode.Translucent,
        // 世界书/正则：全部开启 / 全部关闭 / 恢复默认（= 清覆盖 = 跟随上级）
        // 之前这三行占着列表前三格（用户 2026-11-25 第 4/5 条：挪到右上胶囊，列表只留条目）
        actions = when (target.kind) {
            // 采样这一页的页面级动作：五项一起恢复默认（= 跟随上级）
            ConfigKind.Sampling -> {
                {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        CapsuleIcon(
                            icon = Icons.Filled.MoreVert,
                            contentDescription = "更多",
                        ) { open = true }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(
                                text = { Text("恢复默认") },
                                onClick = {
                                    open = false
                                    when (target.scope) {
                                        ConfigScope.Session -> viewModel.clearSessionSampling()
                                        ConfigScope.Card -> viewModel.clearCardSampling()
                                    }
                                },
                            )
                        }
                    }
                }
            }

            ConfigKind.WorldBook -> {
                {
                    PickerBulkMenu(
                        onAll = {
                            val all = state.worldBooks.map { it.name }.toSet()
                            when (target.scope) {
                                ConfigScope.Session -> viewModel.useSessionWorldBooks(all)
                                ConfigScope.Card -> viewModel.useCardWorldBooks(all)
                            }
                        },
                        onNone = {
                            when (target.scope) {
                                ConfigScope.Session -> viewModel.useSessionWorldBooks(emptySet())
                                ConfigScope.Card -> viewModel.useCardWorldBooks(emptySet())
                            }
                        },
                        onFollow = {
                            when (target.scope) {
                                ConfigScope.Session -> viewModel.useSessionWorldBooks(null)
                                ConfigScope.Card -> viewModel.useCardWorldBooks(null)
                            }
                        },
                        // 没有书时"全部开启"等于"全部关闭"，干脆不给按
                        enabled = state.worldBooks.isNotEmpty(),
                    )
                }
            }

            ConfigKind.Regex -> {
                {
                    PickerBulkMenu(
                        // 三个动作对"整页"生效：用户脚本 + 内置规则都算（用户 2026-11-25 第 15 条）
                        onAll = {
                            val all = state.regexScripts.map { it.name }.filter { it.isNotBlank() }.toSet()
                            when (target.scope) {
                                ConfigScope.Session -> viewModel.useSessionRegexAndBuiltin(all, emptySet())
                                ConfigScope.Card -> viewModel.useCardRegexAndBuiltin(all, emptySet())
                            }
                        },
                        onNone = {
                            val allBuiltin = BuiltinRegex.features.map { it.id }.toSet()
                            when (target.scope) {
                                ConfigScope.Session ->
                                    viewModel.useSessionRegexAndBuiltin(emptySet(), allBuiltin)

                                ConfigScope.Card ->
                                    viewModel.useCardRegexAndBuiltin(emptySet(), allBuiltin)
                            }
                        },
                        onFollow = {
                            when (target.scope) {
                                ConfigScope.Session -> viewModel.useSessionRegexAndBuiltin(null, null)
                                ConfigScope.Card -> viewModel.useCardRegexAndBuiltin(null, null)
                            }
                        },
                        // 内置也有开关时要能按，所以不再只看用户脚本
                        enabled = state.regexScripts.isNotEmpty() || BuiltinRegex.features.isNotEmpty(),
                    )
                }
            }

            else -> null
        },
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
            when (target.kind) {
                ConfigKind.Model -> ModelPickerPageBody(state, viewModel, target.scope)
                ConfigKind.Sampling -> SamplingPickerBody(state, viewModel, target.scope)
                ConfigKind.WorldBook -> WorldBookPickerBody(state, viewModel, target.scope)
                ConfigKind.Regex -> RegexPickerBody(state, viewModel, target.scope)
                ConfigKind.Preset -> PresetPickerBody(state, viewModel, target.scope)
                ConfigKind.FontSize -> FontSizePickerBody(state, viewModel, target.scope)
                ConfigKind.Persona -> PersonaPickerBody(state, viewModel, target.scope)
            }
        }
    }
}

/** 模型页：顶部思考强度滑块（用户：思考强度就放模型里，别再单独一行）+ 模型列表。 */
@Composable
private fun ModelPickerPageBody(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
) {
    val stored = when (scope) {
        ConfigScope.Session -> state.sessionOverride.reasoningEffort
        ConfigScope.Card -> state.cardOverride.reasoningEffort
    }
    ThinkingEffortSlider(
        current = stored,
        effective = state.effectiveReasoningEffort,
        onSelect = {
            when (scope) {
                ConfigScope.Session -> viewModel.useSessionReasoning(it)
                ConfigScope.Card -> viewModel.useCardReasoning(it)
            }
        },
    )
    Spacer(Modifier.height(Space.xs))
    ModelPickerRows(state = state, viewModel = viewModel, scope = scope)
}

/**
 * 采样三级（用户 2026-11-25 第 2 条）：五项都留空 = 跟随上级。
 *
 * 每格里**显示的是这一层的值**，占位符写"跟随上级：X"——否则用户看不出现在到底发的是几。
 * 优先级：会话 > 卡 > 预设 > 全局（和 [ChatViewModel.UiState.effectiveTemperature] 那组 getter 一致）。
 */
@Composable
private fun SamplingPickerBody(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
) {
    val stored = when (scope) {
        ConfigScope.Session -> state.sessionOverride
        ConfigScope.Card -> state.cardOverride
    }
    // 本地草稿：输入 "0." 这种半截值不能立刻被解析成 null 把框清空（和全局采样页同一个理由）
    val drafts = remember(scope) {
        mutableStateMapOf<String, String>().apply {
            put(SamplingField.Temperature.name, stored.temperature?.toString().orEmpty())
            put(SamplingField.MaxTokens.name, stored.maxTokens?.toString().orEmpty())
            put(SamplingField.TopP.name, stored.topP?.toString().orEmpty())
            put(SamplingField.FrequencyPenalty.name, stored.frequencyPenalty?.toString().orEmpty())
            put(SamplingField.PresencePenalty.name, stored.presencePenalty?.toString().orEmpty())
        }
    }

    Text(
        "留空 = 跟随上级（会话 > 卡 > 预设 > 全局）",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SamplingField.entries.forEach { field ->
        val effective = when (field) {
            SamplingField.Temperature -> state.effectiveTemperature.toString()
            SamplingField.MaxTokens -> state.effectiveMaxTokens?.toString() ?: "不限"
            SamplingField.TopP -> state.effectiveTopP?.toString() ?: "不指定"
            SamplingField.FrequencyPenalty -> state.effectiveFrequencyPenalty?.toString() ?: "不指定"
            SamplingField.PresencePenalty -> state.effectivePresencePenalty?.toString() ?: "不指定"
        }
        OutlinedTextField(
            value = drafts[field.name].orEmpty(),
            onValueChange = { text ->
                drafts[field.name] = text
                when (scope) {
                    ConfigScope.Session -> viewModel.useSessionSampling(field, text)
                    ConfigScope.Card -> viewModel.useCardSampling(field, text)
                }
            },
            label = { Text(field.label) },
            placeholder = { Text("跟随上级：$effective") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (field == SamplingField.MaxTokens) {
                    KeyboardType.Number
                } else {
                    KeyboardType.Decimal
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 世界书多选：勾的是"哪些全局书参与"，书自己的启用开关是另一层。 */
@Composable
private fun WorldBookPickerBody(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
) {
    val current = effectiveBookNames(state)
    // 书多的时候按名字搜（用户 2026-11-25 第 16 条）
    var query by remember { mutableStateOf("") }
    val books = remember(query, state.worldBooks) {
        if (query.isBlank()) {
            state.worldBooks
        } else {
            val q = query.trim().lowercase()
            state.worldBooks.filter { it.name.lowercase().contains(q) }
        }
    }
    SearchField(
        value = query,
        placeholder = "搜索世界书",
        onValueChange = { query = it },
    )
    // 列表只剩书本身：「跟随上级 / 全部开启 / 全部关闭」在右上胶囊里（用户 2026-11-25 第 4 条）
    ListGroup(count = books.size) { index, _ ->
        val book = books[index]
        ListRow(
            title = book.name,
            subtitle = "${book.entryCount} 条" + if (book.enabled) "" else " · 全局未启用",
            leading = { ActiveMark(book.name in current) },
            onClick = {
                val next = if (book.name in current) current - book.name else current + book.name
                when (scope) {
                    ConfigScope.Session -> viewModel.useSessionWorldBooks(next)
                    ConfigScope.Card -> viewModel.useCardWorldBooks(next)
                }
            },
        )
    }

    if (state.worldBooks.isEmpty()) {
        Text(
            "还没有导入全局世界书（设置 → 世界书）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (books.isEmpty()) {
        Text(
            "没有匹配的世界书",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 正则多选：勾的是"哪些脚本参与"，脚本自己的 disabled 是另一层。 */
@Composable
private fun RegexPickerBody(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
) {
    val current = effectiveRegexNames(state)
    // 列表只剩脚本本身：三个批量动作在右上胶囊（用户 2026-11-25 第 5 条）
    ListGroup(count = state.regexScripts.size) { index, _ ->
        val script = state.regexScripts[index]
        ListRow(
            title = script.name.ifBlank { "（未命名脚本）" },
            subtitle = if (script.disabled) "脚本本身已停用" else null,
            leading = { ActiveMark(script.name in current) },
            onClick = {
                val name = script.name
                val next = if (name in current) current - name else current + name
                when (scope) {
                    ConfigScope.Session -> viewModel.useSessionRegex(next)
                    ConfigScope.Card -> viewModel.useCardRegex(next)
                }
            },
        )
    }

    if (state.regexScripts.isEmpty()) {
        Text(
            "还没有导入正则脚本（设置 → 正则）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 内置规则也能在这一层开关（用户 2026-11-25 第 15 条）：会话 > 卡 > 全局。
    // 开关写的是**这一层**的覆盖（空集 = 明确全开，null = 跟随）；全局那两档在设置页里。
    SectionLabel("内置")
    val builtinDisabled = state.effectiveBuiltinDisabled
    ListGroup(count = BuiltinRegex.features.size) { index, _ ->
        val feature = BuiltinRegex.features[index]
        ListRow(
            title = feature.title,
            subtitle = feature.description,
            trailing = {
                Switch(
                    checked = feature.id !in builtinDisabled,
                    onCheckedChange = { on ->
                        val next = if (on) builtinDisabled - feature.id else builtinDisabled + feature.id
                        when (scope) {
                            ConfigScope.Session -> viewModel.useSessionBuiltin(next)
                            ConfigScope.Card -> viewModel.useCardBuiltin(next)
                        }
                    },
                )
            },
        )
    }
}

/** 预设单选。 */
@Composable
private fun PresetPickerBody(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
) {
    val stored = when (scope) {
        ConfigScope.Session -> state.sessionOverride.presetName
        ConfigScope.Card -> state.cardOverride.presetName
    }
    val effective = state.effectivePresetName
    val following = stored == null
    // 常驻「跟随上级」（同上：避免插入一行后整列挪位）
    val count = state.presets.size + 1

    ListGroup(count = count) { index, _ ->
        if (index == 0) {
            ListRow(
                title = "跟随上级",
                subtitle = state.activePreset ?: "内置默认",
                leading = { ActiveMark(following) },
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionPreset(null)
                        ConfigScope.Card -> viewModel.useCardPreset(null)
                    }
                },
            )
        } else {
            val preset = state.presets[index - 1]
            ListRow(
                title = preset.name,
                subtitle = if (preset.disabledCount > 0) "关 ${preset.disabledCount} 段" else null,
                leading = { ActiveMark(!following && preset.name == effective) },
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionPreset(preset.name)
                        ConfigScope.Card -> viewModel.useCardPreset(preset.name)
                    }
                },
            )
        }
    }

    if (state.presets.isEmpty()) {
        Text(
            "还没有导入预设（设置 → 预设）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 世界书 / 正则选择页右上角的批量菜单（用户 2026-11-25 第 4、5 条）。
 *
 * 「恢复默认」= 清掉这一层的覆盖 = 跟随上级；和列表里原来那条「跟随上级」是同一个动作。
 */
@Composable
private fun PickerBulkMenu(
    onAll: () -> Unit,
    onNone: () -> Unit,
    onFollow: () -> Unit,
    enabled: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        CapsuleIcon(
            icon = Icons.Filled.MoreVert,
            contentDescription = "更多",
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("全部开启") },
                enabled = enabled,
                onClick = {
                    open = false
                    onAll()
                },
            )
            DropdownMenuItem(
                text = { Text("全部关闭") },
                onClick = {
                    open = false
                    onNone()
                },
            )
            DropdownMenuItem(
                text = { Text("恢复默认") },
                onClick = {
                    open = false
                    onFollow()
                },
            )
        }
    }
}

/**
 * 气泡字号的整页选择（用户 2026-11-26 第 6 条）。
 *
 * 和预设一样是单选，但顶上常驻一行「跟随上级」（清掉这一层的覆盖）—— 它不随覆盖增删，
 * 免得出现/消失时整列挪一格、点错邻居。
 */
@Composable
private fun FontSizePickerBody(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
) {
    val stored = when (scope) {
        ConfigScope.Session -> state.sessionOverride.bubbleFontSize
        ConfigScope.Card -> state.cardOverride.bubbleFontSize
    }
    val following = stored == null
    // 上级的值：会话看卡（没有再落到全局），卡只看全局
    val parent = when (scope) {
        ConfigScope.Session -> state.cardOverride.bubbleFontSize ?: state.bubbleFontSize
        ConfigScope.Card -> state.bubbleFontSize
    }

    ListGroup(count = 1 + BubbleFontSizes.size) { index, _ ->
        if (index == 0) {
            ListRow(
                title = "跟随上级",
                subtitle = "${parent.toInt()}sp · ${bubbleFontSizeLabel(parent)}",
                leading = { ActiveMark(following) },
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionBubbleFontSize(null)
                        ConfigScope.Card -> viewModel.useCardBubbleFontSize(null)
                    }
                },
            )
        } else {
            val size = BubbleFontSizes[index - 1]
            ListRow(
                title = bubbleFontSizeLabel(size),
                subtitle = "${size.toInt()}sp",
                leading = { ActiveMark(!following && stored == size) },
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionBubbleFontSize(size)
                        ConfigScope.Card -> viewModel.useCardBubbleFontSize(size)
                    }
                },
            )
        }
    }
}

/**
 * 人设选择（三级里的中间两层：会话 / 卡）。
 *
 * 「跟随上级」常驻第一行（和其它选择页同一条规矩：它一出现/消失就会把整列挪一格）。
 * 副标题写的是**生效人设的描述首行**，这样不用点进去也知道现在是谁在说话。
 */
@Composable
private fun PersonaPickerBody(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
) {
    val stored = when (scope) {
        ConfigScope.Session -> state.sessionOverride.personaId
        ConfigScope.Card -> state.cardOverride.personaId
    }
    val following = stored == null
    val effective = state.effectivePersona
    val followSummary = when (scope) {
        ConfigScope.Session ->
            state.personas.firstOrNull { it.id == state.cardOverride.personaId }?.name
                ?: state.personas.firstOrNull { it.id == state.personaDefaultId }?.name
                ?: "没有默认人设"

        ConfigScope.Card ->
            state.personas.firstOrNull { it.id == state.personaDefaultId }?.name
                ?: "没有默认人设"
    }

    ListGroup(count = state.personas.size + 1) { index, _ ->
        if (index == 0) {
            ListRow(
                title = "跟随上级",
                subtitle = followSummary,
                leading = { ActiveMark(following) },
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionPersona(null)
                        ConfigScope.Card -> viewModel.useCardPersona(null)
                    }
                },
            )
        } else {
            val persona = state.personas[index - 1]
            ListRow(
                title = persona.name,
                subtitle = persona.description.lineSequence().firstOrNull().orEmpty()
                    .ifBlank { "没有描述" },
                leading = { ActiveMark(!following && persona.id == effective?.id) },
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionPersona(persona.id)
                        ConfigScope.Card -> viewModel.useCardPersona(persona.id)
                    }
                },
            )
        }
    }

    if (state.personas.isEmpty()) {
        Text(
            "还没有人设。到设置 → 人设里先建一个。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
