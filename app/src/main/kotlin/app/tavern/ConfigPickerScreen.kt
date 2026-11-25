package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

        else -> false
    }
    val subtitle = if (target.kind == ConfigKind.WorldBook || target.kind == ConfigKind.Regex) {
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
                        onAll = {
                            val all = state.regexScripts.map { it.name }.filter { it.isNotBlank() }.toSet()
                            when (target.scope) {
                                ConfigScope.Session -> viewModel.useSessionRegex(all)
                                ConfigScope.Card -> viewModel.useCardRegex(all)
                            }
                        },
                        onNone = {
                            when (target.scope) {
                                ConfigScope.Session -> viewModel.useSessionRegex(emptySet())
                                ConfigScope.Card -> viewModel.useCardRegex(emptySet())
                            }
                        },
                        onFollow = {
                            when (target.scope) {
                                ConfigScope.Session -> viewModel.useSessionRegex(null)
                                ConfigScope.Card -> viewModel.useCardRegex(null)
                            }
                        },
                        enabled = state.regexScripts.isNotEmpty(),
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
    val current = effectiveBookNames(state)
    // 列表只剩书本身：「跟随上级 / 全部开启 / 全部关闭」在右上胶囊里（用户 2026-11-25 第 4 条）
    ListGroup(count = state.worldBooks.size) { index, _ ->
        val book = state.worldBooks[index]
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
