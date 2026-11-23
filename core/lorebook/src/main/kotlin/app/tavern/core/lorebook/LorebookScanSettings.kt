package app.tavern.core.lorebook

/**
 * 世界书的全局设置。默认值对齐原版 `world-info.js:69-82` 的初始值。
 *
 * [maxContext] 是模型上下文长度（原版从预设的 `openai_max_context` 来），预算按它的百分比算。
 */
public data class LorebookScanSettings(
    /** 全局扫描深度：每轮拿聊天最后几条去匹配关键字，默认 2。 */
    public val scanDepth: Int = 2,
    /** 预算占比（百分比），默认 25。 */
    public val budgetPercent: Int = 25,
    /** 预算上限（token）；0 = 不限。 */
    public val budgetCap: Int = 0,
    /** 模型上下文总长，用来把百分比换成 token 数。 */
    public val maxContext: Int = 4096,
    /** 递归扫描：被激活条目的内容也参与后续扫描。 */
    public val recursive: Boolean = false,
    /** 最大递归轮数；0 = 不限（此时 minActivations 关闭，反之亦然）。 */
    public val maxRecursionSteps: Int = 0,
    /** 至少激活几条；0 = 关。 */
    public val minActivations: Int = 0,
    /** min activations 向外扩深度的上限；0 = 不额外限制（仍受聊天长度限制）。 */
    public val minActivationsDepthMax: Int = 0,
    public val caseSensitive: Boolean = false,
    public val matchWholeWords: Boolean = false,
    public val useGroupScoring: Boolean = false,
    /** 角色名，用来替换 `{{char}}`。 */
    public val charName: String = "{{char}}",
    /** 用户名，用来替换 `{{user}}`。 */
    public val userName: String = "{{user}}",
    /** 生成类型（`normal` / `continue` / …），给条目的 `triggers` 过滤用。 */
    public val trigger: String = "normal",
    /** 计数用；P1-6 的 `:core:tokenizer` 接上之前默认按 4 字符 ≈ 1 token 估（**估**，夹具里会再收紧）。 */
    public val tokenCounter: LorebookTokenCounter = LorebookTokenCounter.Approximate,
)

/** 只数 token，不关心怎么数。真实实现见后续 `:core:tokenizer`。 */
public fun interface LorebookTokenCounter {
    public fun count(text: String): Int

    public companion object {
        /** 占位实现：4 字符 1 token，向上取整。 */
        public val Approximate: LorebookTokenCounter = LorebookTokenCounter { (it.length + 3) / 4 }
    }
}

/**
 * 与聊天无关、但参与关键字匹配的上下文（原版 `WIGlobalScanData`，`world-info.js:186`）。
 */
public class LorebookGlobalScanData(
    public val personaDescription: String = "",
    public val characterDescription: String = "",
    public val characterPersonality: String = "",
    public val characterDepthPrompt: String = "",
    public val scenario: String = "",
    public val creatorNotes: String = "",
)
