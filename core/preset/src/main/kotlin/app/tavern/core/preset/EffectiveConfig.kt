package app.tavern.core.preset

import app.tavern.core.model.ConfigOverride

/**
 * 「这一场对话真正在用的配置」—— 三级继承（会话 → 角色卡 → 预设/全局）解析之后的结果。
 *
 * 为什么要有它（2026-11-28）：以前 14 个 `effective*` getter 各自写一条 `?:` 链，散在 `UiState` 里，
 * 而 `UiState` 在 `:app` —— **本机编不了、也没有任何断言**。这一块正是"界面显示 A、请求发 B"那类
 * 历史 bug 的根源（[app/AGENTS.md] 专门立过规矩）。抽成纯函数后：契约集中一处、可在本机跑单测。
 *
 * 语义上的三条硬约束（都有测试钉住）：
 * 1. `null` = **跟随上一层**；空集 / 空串 / 0 / false 都是"明确的选择"，不许和 null 混为一谈；
 * 2. 采样五项是**四层**：会话 > 卡 > 预设 > 全局；其余是三层或两层（见 [resolveEffectiveConfig]）；
 * 3. `authorNote` 的正文在这里统一归一（空串 → null）—— 注释里说的"正文为空 = 这一层没有"，
 *    以前靠每个写入点自觉，现在由这里保证。
 */
public data class EffectiveConfig(
    public val personaId: String?,
    public val profileId: String?,
    public val presetName: String?,
    public val reasoningEffort: String?,
    public val worldBookNames: Set<String>?,
    public val regexIds: Set<String>?,
    public val builtinDisabled: Set<String>,
    public val bubbleFontSize: Float,
    public val temperature: Double,
    public val maxTokens: Int?,
    public val topP: Double?,
    public val frequencyPenalty: Double?,
    public val presencePenalty: Double?,
    /** 已归一（空串 → null）；作者注的深度/角色/间隔/位置由调用方另取（它们不是"解析"而是复合）。 */
    public val authorNoteText: String?,
)

/** 全局（最外层）默认值：这些不在 `ConfigOverride` 里，而是设置页/会话状态里的实际值。 */
public data class GlobalDefaults(
    public val personaId: String? = null,
    public val profileId: String? = null,
    public val presetName: String? = null,
    public val bubbleFontSize: Float = 0f,
    public val builtinDisabled: Set<String> = emptySet(),
)

/** 采样五项在界面上是文本框（保留用户输入的原样），所以进解析前是字符串。 */
public data class SamplingTexts(
    public val temperature: String = "",
    public val maxTokens: String = "",
    public val topP: String = "",
    public val frequencyPenalty: String = "",
    public val presencePenalty: String = "",
)

/**
 * 解析生效值。**纯函数**：不读全局变量、不碰 Android、不写日志。
 *
 * @param session 会话级覆盖（最内层）
 * @param card 角色卡级覆盖（中间层）
 * @param sampling 预设里带的那份采样（只在采样五项上参与，排在卡之后、全局之前）
 * @param globals 全局默认值
 * @param texts 采样五项的文本框原样输入（全局那一层）
 */
public fun resolveEffectiveConfig(
    session: ConfigOverride,
    card: ConfigOverride,
    sampling: SamplingPreset? = null,
    globals: GlobalDefaults = GlobalDefaults(),
    texts: SamplingTexts = SamplingTexts(),
): EffectiveConfig = EffectiveConfig(
    // 三层：会话 > 卡 > 全局默认
    personaId = session.personaId ?: card.personaId ?: globals.personaId,
    profileId = session.profileId ?: card.profileId ?: globals.profileId,
    presetName = session.presetName ?: card.presetName ?: globals.presetName,
    // 两层：会话 > 卡（没有全局层 —— null 就是"跟随到底"，也就是不发这个字段）
    reasoningEffort = session.reasoningEffort ?: card.reasoningEffort,
    worldBookNames = session.worldBookNames ?: card.worldBookNames,
    regexIds = session.regexIds ?: card.regexIds,
    // 三层，且空集 ≠ null：空集 = 这一层明确全开
    builtinDisabled = session.builtinDisabled ?: card.builtinDisabled ?: globals.builtinDisabled,
    // 三层：会话 > 卡 > 全局（只影响气泡显示）
    bubbleFontSize = session.bubbleFontSize ?: card.bubbleFontSize ?: globals.bubbleFontSize,
    // 四层：会话 > 卡 > 预设 > 全局文本。解析失败就继续往上落，最后兜默认值
    temperature = session.temperature ?: card.temperature
        ?: sampling?.temperature ?: texts.temperature.toDoubleOrNull() ?: 1.0,
    // maxTokens 的 0 / 负数 = 不限（不往请求里放），所以这里要 takeIf，不能直接 toIntOrNull
    maxTokens = session.maxTokens ?: card.maxTokens
        ?: sampling?.maxTokens ?: texts.maxTokens.toIntOrNull()?.takeIf { it > 0 },
    topP = session.topP ?: card.topP
        ?: sampling?.topP ?: texts.topP.toDoubleOrNull(),
    frequencyPenalty = session.frequencyPenalty ?: card.frequencyPenalty
        ?: sampling?.frequencyPenalty ?: texts.frequencyPenalty.toDoubleOrNull(),
    presencePenalty = session.presencePenalty ?: card.presencePenalty
        ?: sampling?.presencePenalty ?: texts.presencePenalty.toDoubleOrNull(),
    // 正文按**每一层**归一：空串 = 这一层没有，继续往上找。
    // 旧实现是 `(session ?: card)?.takeIf { it.isNotBlank() }` —— 会话层写空串会**截断**继承。
    // 那种状态在运行时不可达（`setSessionAuthorNote` 与持久化两处都把空串归一成 null），
    // 所以这是"只在不同达状态上不同"的有意修正，不是行为变更（测试里两条都钉住）。
    authorNoteText = session.authorNote?.takeIf { it.isNotBlank() }
        ?: card.authorNote?.takeIf { it.isNotBlank() },
)
