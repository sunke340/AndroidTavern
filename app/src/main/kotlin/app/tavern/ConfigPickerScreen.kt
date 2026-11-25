package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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

    GlassDetailScaffold(
        title = target.kind.title,
        // 作用域是"值"，不是说明文案：这一页改的是会话还是这张卡
        subtitle = if (target.scope == ConfigScope.Session) "本会话" else "这张卡",
        onBack = viewModel::closeConfigPicker,
        glass = glass,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .rubberOverscroll(rememberRubberOverscroll())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l, vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            when (target.kind) {
                ConfigKind.Model -> ModelPickerPageBody(state, viewModel, target.scope)
                ConfigKind.WorldBook -> WorldBookPickerBody(state, viewModel, target.scope)
                ConfigKind.Regex -> RegexPickerBody(state, viewModel, target.scope)
                ConfigKind.Preset -> PresetPickerBody(state, viewModel, target.scope)
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

/** 世界书多选：勾的是"哪些全局书参与"，书自己的启用开关是另一层。 */
@Composable
private fun WorldBookPickerBody(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
) {
    val stored = when (scope) {
        ConfigScope.Session -> state.sessionOverride.worldBookNames
        ConfigScope.Card -> state.cardOverride.worldBookNames
    }
    val current = effectiveBookNames(state)
    val all = state.worldBooks.map { it.name }.toSet()
    // 没有书时别摆"全部开启/关闭"两行空动作
    val extras = (if (stored != null) 1 else 0) + if (state.worldBooks.isEmpty()) 0 else 2
    val count = state.worldBooks.size + extras

    ListGroup(count = count) { index, _ ->
        when {
            stored != null && index == 0 -> ListRow(
                title = "跟随上级",
                subtitle = null,
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionWorldBooks(null)
                        ConfigScope.Card -> viewModel.useCardWorldBooks(null)
                    }
                },
            )

            index == (if (stored != null) 1 else 0) -> ListRow(
                title = "全部开启",
                subtitle = "这本书单里的 ${all.size} 本都参与",
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionWorldBooks(all)
                        ConfigScope.Card -> viewModel.useCardWorldBooks(all)
                    }
                },
            )

            index == (if (stored != null) 2 else 1) -> ListRow(
                title = "全部关闭",
                subtitle = null,
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionWorldBooks(emptySet())
                        ConfigScope.Card -> viewModel.useCardWorldBooks(emptySet())
                    }
                },
            )

            else -> {
                val book = state.worldBooks[index - extras]
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
        }
    }

    if (state.worldBooks.isEmpty()) {
        Text(
            "还没有导入全局世界书（设置 → 世界书）",
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
    val stored = when (scope) {
        ConfigScope.Session -> state.sessionOverride.regexIds
        ConfigScope.Card -> state.cardOverride.regexIds
    }
    val current = effectiveRegexNames(state)
    val all = state.regexScripts.map { it.name }.filter { it.isNotBlank() }.toSet()
    val extras = (if (stored != null) 1 else 0) + if (state.regexScripts.isEmpty()) 0 else 2
    val count = state.regexScripts.size + extras

    ListGroup(count = count) { index, _ ->
        when {
            stored != null && index == 0 -> ListRow(
                title = "跟随上级",
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionRegex(null)
                        ConfigScope.Card -> viewModel.useCardRegex(null)
                    }
                },
            )

            index == (if (stored != null) 1 else 0) -> ListRow(
                title = "全部开启",
                subtitle = "${all.size} 条都参与",
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionRegex(all)
                        ConfigScope.Card -> viewModel.useCardRegex(all)
                    }
                },
            )

            index == (if (stored != null) 2 else 1) -> ListRow(
                title = "全部关闭",
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionRegex(emptySet())
                        ConfigScope.Card -> viewModel.useCardRegex(emptySet())
                    }
                },
            )

            else -> {
                val script = state.regexScripts[index - extras]
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
        }
    }

    if (state.regexScripts.isEmpty()) {
        Text(
            "还没有导入正则脚本（设置 → 正则）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val hasFollow = stored != null
    val count = state.presets.size + if (hasFollow) 1 else 0

    ListGroup(count = count) { index, _ ->
        if (hasFollow && index == 0) {
            ListRow(
                title = "跟随上级",
                subtitle = state.activePreset ?: "内置默认",
                leading = { ActiveMark(false) },
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionPreset(null)
                        ConfigScope.Card -> viewModel.useCardPreset(null)
                    }
                },
            )
        } else {
            val preset = state.presets[index - if (hasFollow) 1 else 0]
            ListRow(
                title = preset.name,
                subtitle = if (preset.disabledCount > 0) "关 ${preset.disabledCount} 段" else null,
                leading = { ActiveMark(preset.name == effective) },
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
