package app.tavern.core.lorebook

import kotlin.random.Random

/**
 * 世界书跑一遍所需的**最小业务输入**。
 *
 * 为什么要有它（2026-11-28，B2）：这一整套流程原来写在 `ChatViewModel.scanWorldInfo` 里，
 * 而且形参是**整个 `UiState`** —— 一个纯计算却依赖整个界面状态，既测不了也读不出"它到底依赖什么"。
 * 这里只列真正用到的字段：卡内嵌书、两套开关（禁用/强制开启）、全局书（名字 + 是否启用）、
 * 三级选择（`onlyBookNames`）、历史、扫描状态、预算、计数器、随机源。
 *
 * 注意 `random`：`LorebookScanner` 对"概率条目"要抽号，所以**默认参数（`Random.Default`）下
 * 同一输入两次运行结果可以不同**。要可复现就传 `Random(seed)` —— 测试都这么干。
 */
public data class WorldInfoInputs(
    /** 卡内嵌的条目（**原样**传入，禁用/强制开启由这里应用 —— 这样规则才可测）。 */
    public val cardEntries: List<LorebookEntry> = emptyList(),
    /** 本机把哪些条目关掉了（卡里没写 enabled 的，解析时会被标 disabled）。 */
    public val disabledUids: Set<Int> = emptySet(),
    /** 本机"强制开启"的条目：在解析时被标 disabled，这里翻回来。 */
    public val forcedUids: Set<Int> = emptySet(),
    /** 全局书：名字 + 它自己的启用开关。 */
    public val globalBooks: List<GlobalBook> = emptyList(),
    /** 三级继承的"这场对话带哪些全局书"：null = 按每本书自己的开关；空集 = 一本都不带。 */
    public val onlyBookNames: Set<String>? = null,
    /** 全局书的条目（调用方按书读好，这里只按名字取）。 */
    public val globalEntriesByBook: Map<String, List<LorebookEntry>> = emptyMap(),
    public val charName: String = "{{char}}",
    public val userName: String = "{{user}}",
    public val characterDescription: String = "",
    public val characterPersonality: String = "",
    public val scenario: String = "",
    /** 聊天历史（只用到"是不是用户说的"与正文）。 */
    public val history: List<WorldInfoMessage> = emptyList(),
    /** sticky / cooldown 的计时状态（上一轮的结果原样传回来）。 */
    public val scanState: LorebookScanState = LorebookScanState(),
    /** 提示词能用的上下文长度：预算按它的百分比算。 */
    public val maxContext: Int = DEFAULT_MAX_CONTEXT,
    public val tokenCounter: LorebookTokenCounter = LorebookTokenCounter.Approximate,
    /** 概率条目要抽号；默认不可复现，测试传固定 seed。 */
    public val random: Random = Random.Default,
)

/** 一条聊天历史，只留世界书扫描要用的两样东西（避免把 `ChatMessage` 依赖带进本模块）。 */
public class WorldInfoMessage(public val isUser: Boolean, public val text: String)

/** 一本全局世界书：名字 + 它自己的启用开关。 */
public class GlobalBook(public val name: String, public val enabled: Boolean)

/**
 * 预算用的上下文长度：跟原版默认预设一致（与 Max tokens 无关的那部分）。
 * 原版从预设的 `openai_max_context` 来，我们暂不接那一项，先钉这个值。
 */
public const val DEFAULT_MAX_CONTEXT: Int = 3795

/**
 * 跑一遍世界书激活。**纯计算**：不读文件、不碰数据库、不写日志、不改输入对象。
 *
 * 顺序与原实现一致，且**这是兼容行为**：
 * 1. 卡内嵌书在前、全局书在后（原版 `character_first` 策略）；
 * 2. 内嵌书先按本机开关过滤，再按"强制开启"翻回来；
 * 3. 全局书按三级选择（`onlyBookNames`）或书自己的开关过滤；
 * 4. 传给扫描器的聊天串带名字前缀（`原版 chatForWI` 就是 `${name}: ${mes}`）——
 *    少了它，`matchPersonaDescription` 和"人里点名"的条目会匹配不到。
 *
 * 概率条目会消耗 [WorldInfoInputs.random]；抽号次数与顺序与实现绑定，改这里要重录夹具。
 */
public fun scanWorldInfo(inputs: WorldInfoInputs): LorebookScanResult {
    val cardEntries = inputs.cardEntries
        .filter { it.uid !in inputs.disabledUids }
        .map { if (it.uid in inputs.forcedUids) it.withDisabled(false) else it }
    val globalEntries = inputs.globalBooks
        .filter { book -> inputs.onlyBookNames?.let { book.name in it } ?: book.enabled }
        .flatMap { inputs.globalEntriesByBook[it.name].orEmpty() }
    val entries = cardEntries + globalEntries
    if (entries.isEmpty()) return LorebookScanResult()

    val settings = LorebookScanSettings(
        charName = inputs.charName,
        userName = inputs.userName,
        maxContext = inputs.maxContext,
        tokenCounter = inputs.tokenCounter,
    )
    val chat = inputs.history.map { message ->
        val name = if (message.isUser) inputs.userName else inputs.charName
        "$name: ${message.text}"
    }
    val global = LorebookGlobalScanData(
        characterDescription = inputs.characterDescription,
        characterPersonality = inputs.characterPersonality,
        scenario = inputs.scenario,
    )
    return LorebookScanner(random = inputs.random)
        .scan(entries, chat, settings, inputs.scanState, global)
}
