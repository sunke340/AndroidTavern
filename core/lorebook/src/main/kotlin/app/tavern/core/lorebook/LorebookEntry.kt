package app.tavern.core.lorebook

/**
 * 世界书条目在提示词里的落点。数值对齐原版 `world_info_position`（`world-info.js:855`）。
 */
public enum class LorebookPosition(public val value: Int) {
    /** `before_char`：插在角色描述之前（默认 `prompt_order` 的 `worldInfoBefore`）。 */
    BeforeChar(0),

    /** `after_char`：插在世界书之后、示例对话之前（`worldInfoAfter`）。 */
    AfterChar(1),

    /** 作者注上方。 */
    AuthorNoteTop(2),

    /** 作者注下方。 */
    AuthorNoteBottom(3),

    /** 按深度插进聊天历史（`at_depth`）。 */
    AtDepth(4),

    /** 示例对话开头。 */
    ExampleTop(5),

    /** 示例对话结尾。 */
    ExampleBottom(6),

    /** 出口（outlet），要由别的扩展消费，本实现先记着不注入。 */
    Outlet(7),
    ;

    public companion object {
        public fun fromValue(value: Int?): LorebookPosition =
            entries.firstOrNull { it.value == (value ?: 0) } ?: BeforeChar
    }
}

/**
 * 次要关键字的组合方式。数值对齐原版 `world_info_logic`（`world-info.js:33`）。
 */
public enum class SelectiveLogic(public val value: Int) {
    /** 0：主键 + 任意一个次键（默认）。 */
    AndAny(0),

    /** 1：次键里有一个**不**出现就激活。 */
    NotAll(1),

    /** 2：次键全都不出现才激活。 */
    NotAny(2),

    /** 3：次键全都出现才激活。 */
    AndAll(3),
    ;

    public companion object {
        public fun fromValue(value: Int?): SelectiveLogic =
            entries.firstOrNull { it.value == (value ?: 0) } ?: AndAny
    }
}

/**
 * 一条世界书条目。字段名与含义对齐原版 `convertCharacterBook()`（`world-info.js:5617`）：
 * 卡文件用 `character_book.entries[i]` 的名字，这里换成 Kotlin 习惯的驼峰，但**语义一致**。
 *
 * 默认值也与原版一致（SillyTavern 1.19.1，AGPL-3.0）：`order = 100`、`depth = 4`、`probability = 100`、`useProbability = true`、
 * `groupWeight = 100`、`selectiveLogic = AndAny`。
 *
 * [world] 是"这本书的名字"（卡内嵌书用卡名，全局书用文件名），和 [uid] 一起构成唯一标识
 * [id]。原版用这两个字段去聊天元数据里找 sticky/cooldown 的计时记录。
 */
public class LorebookEntry(
    public val world: String = "",
    public val uid: Int = 0,
    public val keys: List<String> = emptyList(),
    public val secondaryKeys: List<String> = emptyList(),
    public val content: String = "",
    /** 常驻：不看关键字，每轮都注入。 */
    public val constant: Boolean = false,
    /** 原版现在恒为 true（老书里的 `selective` 字段），只有它和 secondaryKeys 同时成立才检查次键。 */
    public val selective: Boolean = false,
    /** 插入序号，越大越靠前（原版 `sortFn = (a,b) => b.order - a.order`，`world-info.js:88`）。 */
    public val order: Int = 100,
    public val position: LorebookPosition = LorebookPosition.BeforeChar,
    /** 原版叫 `disable`，卡文件叫 `enabled`（取反）。 */
    public val disabled: Boolean = false,
    public val probability: Int = 100,
    public val useProbability: Boolean = true,
    /** `at_depth` 用的深度，默认 4。 */
    public val depth: Int = 4,
    public val selectiveLogic: SelectiveLogic = SelectiveLogic.AndAny,
    /** 包含组：同一组里每轮最多活一条。逗号分隔的多个组名在 [group] 里原样保留。 */
    public val group: String = "",
    public val groupOverride: Boolean = false,
    public val groupWeight: Int = 100,
    /** 条目自己的扫描深度；null = 用全局设置。 */
    public val scanDepth: Int? = null,
    /** null = 用全局设置。 */
    public val caseSensitive: Boolean? = null,
    /** null = 用全局设置。 */
    public val matchWholeWords: Boolean? = null,
    /** null = 用全局设置。 */
    public val useGroupScoring: Boolean? = null,
    /** 递归扫描时不参与触发（但可以被别的条目带出来）。 */
    public val excludeRecursion: Boolean = false,
    /** 被递归带出来之后，不再把内容写进递归缓冲。 */
    public val preventRecursion: Boolean = false,
    /** 0 = 不延迟；>0 = 第几层递归才允许激活（卡里是布尔，true 记成 1）。 */
    public val delayUntilRecursion: Int = 0,
    /** 粘滞轮数；null/0 = 关。 */
    public val sticky: Int? = null,
    /** 冷却轮数；null/0 = 关。 */
    public val cooldown: Int? = null,
    /** 前 N 条消息内不出现（按聊天条数算）。 */
    public val delay: Int? = null,
    /** 只在指定生成类型下激活（`normal` / `continue` / …）；空 = 不限。 */
    public val triggers: List<String> = emptyList(),
    /** 不受 token 预算限制，但预算溢出后仍会把它后面的普通条目挡住。 */
    public val ignoreBudget: Boolean = false,
    /** `at_depth` 注入用的角色（0=system，1=user，2=assistant）。 */
    public val role: Int? = null,
    // ---- 扫描范围：把卡的哪些字段也纳入关键字匹配 ----
    public val matchPersonaDescription: Boolean = false,
    public val matchCharacterDescription: Boolean = false,
    public val matchCharacterPersonality: Boolean = false,
    public val matchCharacterDepthPrompt: Boolean = false,
    public val matchScenario: Boolean = false,
    public val matchCreatorNotes: Boolean = false,
    /**
     * 正文开头的 `@@` 装饰器（[WorldBookDecorators.parse] 的结果，含 `@@` 前缀）。
     * 目前只认 `@@activate` / `@@dont_activate`；解析时这两行已经从 [content] 里剥掉了。
     */
    public val decorators: List<String> = emptyList(),
) {
    /** 唯一标识，格式与原版 `world.uid` 一致。 */
    public val id: String get() = "$world.$uid"

    override fun toString(): String = "LorebookEntry($id, keys=$keys)"
}

/**
 * 只改"开/关"这一个字段。字段全是 `val`，所以只能重建；世界书条目的开关是应用侧最常见的操作。
 */
public fun LorebookEntry.withDisabled(disabled: Boolean): LorebookEntry = LorebookEntry(
    world = world,
    uid = uid,
    keys = keys,
    secondaryKeys = secondaryKeys,
    content = content,
    constant = constant,
    selective = selective,
    order = order,
    position = position,
    disabled = disabled,
    probability = probability,
    useProbability = useProbability,
    depth = depth,
    selectiveLogic = selectiveLogic,
    group = group,
    groupOverride = groupOverride,
    groupWeight = groupWeight,
    scanDepth = scanDepth,
    caseSensitive = caseSensitive,
    matchWholeWords = matchWholeWords,
    useGroupScoring = useGroupScoring,
    excludeRecursion = excludeRecursion,
    preventRecursion = preventRecursion,
    delayUntilRecursion = delayUntilRecursion,
    sticky = sticky,
    cooldown = cooldown,
    delay = delay,
    triggers = triggers,
    ignoreBudget = ignoreBudget,
    role = role,
    matchPersonaDescription = matchPersonaDescription,
    matchCharacterDescription = matchCharacterDescription,
    matchCharacterPersonality = matchCharacterPersonality,
    matchCharacterDepthPrompt = matchCharacterDepthPrompt,
    matchScenario = matchScenario,
    matchCreatorNotes = matchCreatorNotes,
    decorators = decorators,
)
