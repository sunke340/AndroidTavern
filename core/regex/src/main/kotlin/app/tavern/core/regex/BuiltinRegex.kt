package app.tavern.core.regex

/**
 * 内置的**显示层**正则（用户 2026-11-25 定的方案：做成"默认正则"，可关，关掉就是原文）。
 *
 * 为什么是正则而不是硬编码清洗：
 * - 原版 ST 的显示流程本来就是"正则脚本 → HTML → 渲染"，这里的规则正好能落在那条路上；
 * - 用户可以按熟悉的开关语义操作（设置 → 正则 里的一组「内置」），关掉等于不跑这条；
 * - 只影响**显示**：存档、导出、`查看请求`、发给模型的提示词全是原文（[RegexPlacement.AiOutput]）。
 *
 * [rules] 的输出里会带 `<i>` / `<b>` 这类内联标签，由 [InlineMarkup] 解析成带样式的片段；
 * 不认识的标签原样显示（不引入 HTML / WebView）。
 */
public class BuiltinRegexFeature(
    /** 稳定 id：开关状态按它存（改名不影响用户设置）。 */
    public val id: String,
    public val title: String,
    public val description: String,
    /** 按顺序执行。 */
    public val rules: List<RegexScript>,
)

public object BuiltinRegex {

    public const val CLEAN_TAGS: String = "clean-tags"
    public const val EMPHASIS: String = "emphasis"

    /**
     * 全部内置项，顺序 = 执行顺序：先清包裹标签，再做强调。
     *
     * 标签白名单只放模型常见的"包裹壳"：`content / output / response / message / text / answer`。
     * 不按"所有尖括号"清 —— 用户可能故意展示 XML（用户 2026-11-25：只清白名单）。
     */
    public val features: List<BuiltinRegexFeature> = listOf(
        BuiltinRegexFeature(
            id = CLEAN_TAGS,
            title = "清理包裹标签",
            description = "去掉正文里单独出现的 </content>、<output> 等包裹标签",
            rules = listOf(
                RegexScript(
                    name = "内置 · 清理包裹标签",
                    findRegex = "/<\\/?(?:content|output|response|message|text|answer)>/gi",
                    replaceString = "",
                    placement = setOf(RegexPlacement.AiOutput),
                ),
            ),
        ),
        BuiltinRegexFeature(
            id = EMPHASIS,
            title = "动作描写强调",
            description = "*斜体*、**粗体**、_斜体_ 按原版 Markdown 的写法渲染",
            rules = listOf(
                // 粗体必须先跑：**x** 变成标签之后，剩下的单个 * 才是斜体
                RegexScript(
                    name = "内置 · 强调：粗体",
                    findRegex = "/\\*\\*([^*\\n]+)\\*\\*/g",
                    replaceString = "<b>$1</b>",
                    placement = setOf(RegexPlacement.AiOutput),
                ),
                // 单个星号斜体：开符后面不能是空白（否则行首的 "* 列表项" 会被误伤）；
                // `(?<!\\)` 让 `\*` 保持转义语义（原版 Markdown 里它是字面星号）
                RegexScript(
                    name = "内置 · 强调：星号斜体",
                    findRegex = "/(?<!\\\\)\\*([^\\s*](?:[^*\\n]*[^\\s*])?)(?<!\\\\)\\*/g",
                    replaceString = "<i>$1</i>",
                    placement = setOf(RegexPlacement.AiOutput),
                ),
                // 下划线斜体：前后都不能是下划线。**不加 `\b`** —— 原版 showdown 对
                // `foo_bar_baz` 也渲染成 foo<em>bar</em>baz（夹具 P1-6 里那条就是它）
                RegexScript(
                    name = "内置 · 强调：下划线斜体",
                    findRegex = "/(?<!_)_(?!_)([^_\\n]+?)(?<!_)_(?!_)/g",
                    replaceString = "<i>$1</i>",
                    placement = setOf(RegexPlacement.AiOutput),
                ),
            ),
        ),
    )

    /** 按"关掉哪些 id"取出要跑的规则（关掉的整项都不跑）。 */
    public fun rules(disabledIds: Set<String>): List<RegexScript> =
        features.filter { it.id !in disabledIds }.flatMap { it.rules }
}
