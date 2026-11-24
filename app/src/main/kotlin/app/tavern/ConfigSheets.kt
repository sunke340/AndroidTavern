package app.tavern

import app.tavern.data.config.ConfigOverride

/** 配置项的作用域：会话级（最内层）还是角色卡级（中间层）。 */
internal enum class ConfigScope { Session, Card }

/**
 * 三级继承的"选择抽屉"。
 *
 * 为什么用动作单而不是新写四个选择页：这些选择都是"一列选项里挑一个/几个"，现有动作单
 * 已经是全 App 的统一语言（长按删除、切换模型都走它）；再写四个页面，等于把同一种交互
 * 实现四遍，还会各带一套返回栈。
 *
 * 多选（世界书 / 正则）用"点了不关、原地刷新"：动作单在每行点击后先 `dismiss()` 再执行，
 * 同一个回调里再 `show()` 一次会合并成一次状态变更 —— 抽屉不闪、✓ 就地挪过去。
 */
internal fun showModelSheet(
    sheet: SheetController,
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
) {
    val thisLevel = when (scope) {
        ConfigScope.Session -> state.sessionOverride.profileId
        ConfigScope.Card -> state.cardOverride.profileId
    }
    val effectiveId = state.effectiveProfileId
    val followTarget = when (scope) {
        ConfigScope.Session -> state.cardOverride.profileId?.let { id ->
            state.profiles.firstOrNull { it.id == id }?.name
        } ?: state.profiles.firstOrNull { it.isDefault }?.name

        ConfigScope.Card -> state.profiles.firstOrNull { it.isDefault }?.name
    }

    val actions = mutableListOf<SheetAction>()
    if (thisLevel != null) {
        val upper = if (followTarget != null) "（$followTarget）" else ""
        actions += SheetAction("跟随上级$upper") {
            when (scope) {
                ConfigScope.Session -> viewModel.clearSessionProfile()
                ConfigScope.Card -> viewModel.useCardProfile(null)
            }
        }
    }
    if (state.profiles.isEmpty()) {
        actions += SheetAction("还没有模型档案（设置 → 模型）") { }
    } else {
        state.profiles.forEach { profile ->
            val mark = if (profile.id == effectiveId) "✓ " else ""
            actions += SheetAction("$mark${profile.name} · ${profile.model}") {
                when (scope) {
                    ConfigScope.Session -> viewModel.useSessionProfile(profile.id)
                    ConfigScope.Card -> viewModel.useCardProfile(profile.id)
                }
            }
        }
    }
    if (scope == ConfigScope.Session) {
        actions += reasoningRows(state.sessionOverride.reasoningEffort, viewModel)
    }
    sheet.show(title = "模型", actions = actions)
}

/** 思考强度：null = 跟随档案，`"off"` = 明确关掉。 */
private fun reasoningRows(
    current: String?,
    viewModel: ChatViewModel,
): List<SheetAction> {
    val rows: List<Pair<String, String?>> = listOf(
        "跟随档案" to null,
        "关" to "off",
        "低" to "low",
        "中" to "medium",
        "高" to "high",
    )
    return rows.map { (label, value) ->
        val mark = if (current == value) "✓ " else ""
        SheetAction("思考强度：$mark$label") { viewModel.useSessionReasoning(value) }
    }
}

internal fun showPresetSheet(
    sheet: SheetController,
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
) {
    val thisLevel = when (scope) {
        ConfigScope.Session -> state.sessionOverride.presetName
        ConfigScope.Card -> state.cardOverride.presetName
    }
    val effective = state.effectivePresetName
    val actions = mutableListOf<SheetAction>()
    if (thisLevel != null) {
        actions += SheetAction("跟随上级") {
            when (scope) {
                ConfigScope.Session -> viewModel.useSessionPreset(null)
                ConfigScope.Card -> viewModel.useCardPreset(null)
            }
        }
    }
    state.presets.forEach { preset ->
        val mark = if (preset.name == effective) "✓ " else ""
        // 预设页会显示"关掉几段"，这里排障时也用得上，顺手带上
        val extra = if (preset.disabledCount > 0) " · 关 ${preset.disabledCount}" else ""
        actions += SheetAction("$mark${preset.name}$extra") {
            when (scope) {
                ConfigScope.Session -> viewModel.useSessionPreset(preset.name)
                ConfigScope.Card -> viewModel.useCardPreset(preset.name)
            }
        }
    }
    if (state.presets.isEmpty()) {
        actions += SheetAction("还没有导入预设（设置 → 预设）") { }
    }
    sheet.show(title = "预设", actions = actions)
}

/**
 * 世界书多选：勾的是"**哪些全局书参与这场对话**"，和每本书自己的启用开关是两层
 * （这一层没指定时，按书自己的开关来）。
 */
internal fun showWorldBookSheet(
    sheet: SheetController,
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
    current: Set<String>,
) {
    val all = state.worldBooks.map { it.name }.toSet()
    val thisLevel = when (scope) {
        ConfigScope.Session -> state.sessionOverride.worldBookNames
        ConfigScope.Card -> state.cardOverride.worldBookNames
    }
    val actions = mutableListOf<SheetAction>()
    if (thisLevel != null) {
        actions += SheetAction("跟随上级") {
            when (scope) {
                ConfigScope.Session -> viewModel.useSessionWorldBooks(null)
                ConfigScope.Card -> viewModel.useCardWorldBooks(null)
            }
        }
    }
    actions += SheetAction("全部开启") {
        when (scope) {
            ConfigScope.Session -> viewModel.useSessionWorldBooks(all)
            ConfigScope.Card -> viewModel.useCardWorldBooks(all)
        }
    }
    actions += SheetAction("全部关闭") {
        when (scope) {
            ConfigScope.Session -> viewModel.useSessionWorldBooks(emptySet())
            ConfigScope.Card -> viewModel.useCardWorldBooks(emptySet())
        }
    }
    if (state.worldBooks.isEmpty()) {
        actions += SheetAction("还没有全局世界书（设置 → 世界书）") { }
    }
    state.worldBooks.forEach { book ->
        val mark = if (book.name in current) "✓ " else ""
        actions += SheetAction("$mark${book.name} · ${book.entryCount} 条") {
            val next = if (book.name in current) current - book.name else current + book.name
            when (scope) {
                ConfigScope.Session -> viewModel.useSessionWorldBooks(next)
                ConfigScope.Card -> viewModel.useCardWorldBooks(next)
            }
            // 原地刷新（dismiss + show 在同一帧，不会真的关掉抽屉）
            showWorldBookSheet(sheet, state, viewModel, scope, next)
        }
    }
    sheet.show(title = "世界书", actions = actions)
}

/** 正则多选：勾的是"哪些脚本参与"，和脚本自己的 disabled 是两层。 */
internal fun showRegexSheet(
    sheet: SheetController,
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
    current: Set<String>,
) {
    val all = state.regexScripts.map { it.name }.filter { it.isNotBlank() }.toSet()
    val thisLevel = when (scope) {
        ConfigScope.Session -> state.sessionOverride.regexIds
        ConfigScope.Card -> state.cardOverride.regexIds
    }
    val actions = mutableListOf<SheetAction>()
    if (thisLevel != null) {
        actions += SheetAction("跟随上级") {
            when (scope) {
                ConfigScope.Session -> viewModel.useSessionRegex(null)
                ConfigScope.Card -> viewModel.useCardRegex(null)
            }
        }
    }
    actions += SheetAction("全部开启") {
        when (scope) {
            ConfigScope.Session -> viewModel.useSessionRegex(all)
            ConfigScope.Card -> viewModel.useCardRegex(all)
        }
    }
    actions += SheetAction("全部关闭") {
        when (scope) {
            ConfigScope.Session -> viewModel.useSessionRegex(emptySet())
            ConfigScope.Card -> viewModel.useCardRegex(emptySet())
        }
    }
    state.regexScripts.forEach { script ->
        val name = script.name.ifBlank { "（未命名脚本）" }
        val mark = if (script.name in current) "✓ " else ""
        actions += SheetAction("$mark$name") {
            val next = if (script.name in current) current - script.name else current + script.name
            when (scope) {
                ConfigScope.Session -> viewModel.useSessionRegex(next)
                ConfigScope.Card -> viewModel.useCardRegex(next)
            }
            showRegexSheet(sheet, state, viewModel, scope, next)
        }
    }
    if (state.regexScripts.isEmpty()) {
        actions += SheetAction("还没有导入正则（设置 → 正则）") { }
    }
    sheet.show(title = "正则", actions = actions)
}

// ---- 行上显示的"值 · 来源" -------------------------------------------------

/** 这一项是谁定的：会话 / 角色卡 / 全局。 */
internal fun sourceOf(scope: ConfigScope, sessionSet: Boolean, cardSet: Boolean): String = when {
    scope == ConfigScope.Session && sessionSet -> "会话"
    cardSet -> "角色卡"
    else -> "全局"
}

/** 解析后的世界书集合（用于显示"参与几本"）。 */
internal fun effectiveBookNames(state: ChatViewModel.UiState): Set<String> =
    state.effectiveWorldBookNames ?: state.worldBooks.filter { it.enabled }.map { it.name }.toSet()

/** 解析后的正则集合。 */
internal fun effectiveRegexNames(state: ChatViewModel.UiState): Set<String> =
    state.effectiveRegexIds ?: state.regexScripts.map { it.name }.filter { it.isNotBlank() }.toSet()

/** 会话里是否已经有用户消息（开场白切换只看这个）。 */
internal fun hasUserMessage(state: ChatViewModel.UiState): Boolean =
    state.transcript.any { it.role == app.tavern.core.model.ChatRole.User }

/** 卡里的一层配置是否全是"跟随"（用于"这一层什么都没改"的提示，可选）。 */
internal fun ConfigOverride.isFollowingAll(): Boolean = isEmpty
