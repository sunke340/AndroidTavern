package app.tavern.data.config

/**
 * 配置覆盖（三级继承的最内层机关）：会话 → 角色卡 → 全局。
 *
 * 每个字段 `null` = **跟随上级**，非 null = 这一层自己指定。这样"跟随"和"恰好选了默认值"
 * 是两种状态，界面才能如实显示「跟随角色卡 · 某模型」。
 *
 * 只存"这台设备上的选择"：不改卡文件（写回 PNG 要备份与校验，是另一批），也不改全局配置。
 */
data class ConfigOverride(
    /** 用哪个模型档案；null = 跟随。 */
    val profileId: String? = null,
    /**
     * 思考强度：null = 跟随；`"off"` = 明确关掉（不发 reasoning_effort）；其余原样发。
     */
    val reasoningEffort: String? = null,
    /** 用哪份预设；null = 跟随。 */
    val presetName: String? = null,
    /**
     * 哪些**全局**世界书参与：null = 跟随（按书自己的启用开关）；
     * 空集 = 全都不要（"这场对话不带任何全局书"是合法选择，所以不能和 null 混成一种）。
     */
    val worldBookNames: Set<String>? = null,
    /**
     * 哪些正则脚本参与：null = 跟随（全部启用）；空集 = 全部关掉。
     * 元素是脚本的稳定 id（`RegexScript` 的 name，导入时保证唯一）。
     */
    val regexIds: Set<String>? = null,
    /**
     * 采样覆盖（用户 2026-11-25 第 2 条）：每项 null = 跟随上一级。
     * 优先级：会话 > 卡 > 预设 > 全局。
     */
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    /**
     * 关掉哪些**内置**规则（`BuiltinRegex.features` 的 id）。null = 跟随上一级；
     * 空集 = 全开（明确打开）。用户 2026-11-25 第 15 条：卡/会话页也要能开关默认正则。
     */
    val builtinDisabled: Set<String>? = null,
    /**
     * 聊天气泡字号（sp）：null = 跟随上级。三级：会话 > 卡 > 全局（用户 2026-11-26 第 6 条）。
     * 只影响聊天页气泡正文的显示，不改存档、导出与提示词。
     */
    val bubbleFontSize: Float? = null,
    /**
     * 作者注（P1-7）：正文 / 深度 / 角色 / 间隔。四项一起看：正文为空 = 没有作者注。
     * 原版把它按间隔插进聊天历史，不是系统提示里的固定一段。
     */
    val authorNote: String? = null,
    val authorNoteDepth: Int? = null,
    /** `System` / `User` / `Assistant`（原版 `note_role`）。 */
    val authorNoteRole: String? = null,
    val authorNoteInterval: Int? = null,
    /**
     * 这一层用哪个人设（`Persona.id`）。null = 跟随上级；三级：会话 > 卡 > 全局默认。
     * 生效人设的**名字**就是 `{{user}}`，**描述**按 `prompt_order` 里的 `personaDescription` 进提示词。
     */
    val personaId: String? = null,
    /**
     * 卡级专用：默认用第几条开场白。0 = `first_mes`，N = `alternateGreetings[N-1]`。
     * 会话级不用这一项（它读的是已经写进库里的第一条消息）。
     */
    val greetingIndex: Int? = null,
) {
    /** 全是"跟随"？那这条覆盖不必落盘。 */
    val isEmpty: Boolean
        get() = profileId == null && reasoningEffort == null && presetName == null &&
            personaId == null && authorNote == null && authorNoteDepth == null &&
            authorNoteRole == null && authorNoteInterval == null &&
            worldBookNames == null && regexIds == null && builtinDisabled == null &&
            bubbleFontSize == null &&
            temperature == null && maxTokens == null && topP == null &&
            frequencyPenalty == null && presencePenalty == null &&
            greetingIndex == null
}

/** 按作用域读写覆盖：`session:<会话 id>` / `card:<卡 id>`。 */
interface ConfigOverrideRepository {
    fun load(scope: String): ConfigOverride

    fun save(scope: String, value: ConfigOverride)

    fun clear(scope: String)

    companion object {
        fun session(sessionId: String): String = "session:$sessionId"

        fun card(cardId: String): String = "card:$cardId"
    }
}
