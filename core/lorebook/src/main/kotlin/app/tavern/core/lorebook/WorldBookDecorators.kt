package app.tavern.core.lorebook

/**
 * 世界书条目正文开头的 `@@` 装饰器。
 *
 * 语义对齐原版 `parseDecorators()`（SillyTavern 1.19.1，AGPL-3.0，`public/scripts/world-info.js`），
 * 包括它的几个怪处：
 * - 只看正文**开头**连续以 `@@` 开头的行，遇到第一行不是 `@@` 的就从那一行起算正文；
 * - 已知装饰器只有 `@@activate` / `@@dont_activate`（原版 `KNOWN_DECORATORS`，`:100`）；
 * - **不认识的装饰器行也会被剥掉**（只是不记录）—— 原版就是这么写的，别"顺手保留"；
 * - `@@@xxx` 是转义：`@` 多一个时，这一行在没被"未知装饰器降级"之前被**整行跳过**
 *   （等于关掉这个装饰器），既不算装饰器也不留在正文里；
 * - 如果整段正文全是 `@@` 行（没有正文行），原版不改 `newContent` —— 正文保持原样
 *   （连装饰器行一起）。这里同样保留这个怪处，免得自己"修"出与原版不同的字节。
 */
public object WorldBookDecorators {

    /** 原版认得的装饰器（`KNOWN_DECORATORS`）。 */
    public val KNOWN: List<String> = listOf("@@activate", "@@dont_activate")

    /** `@@activate`：不看关键字，扫描时就激活。 */
    public const val ACTIVATE: String = "@@activate"

    /** `@@dont_activate`：关键字命中也不激活。 */
    public const val DONT_ACTIVATE: String = "@@dont_activate"

    /**
     * @return 装饰器列表（原样字符串，含 `@@` 前缀）与**去掉装饰器行之后**的正文。
     */
    public fun parse(content: String): Pair<List<String>, String> {
        if (!content.startsWith("@@")) return emptyList<String>() to content

        val lines = content.split('\n')
        var newContent = content
        val decorators = mutableListOf<String>()
        var fallbacked = false

        for (index in lines.indices) {
            val line = lines[index]
            // 第一行不是 @@ 就说明没有装饰器：正文从这行起算（原版用 break，不是 continue）
            if (!line.startsWith("@@")) {
                newContent = lines.drop(index).joinToString("\n")
                break
            }
            val escaped = line.startsWith("@@@")
            // 转义行且此前没被未知装饰器降级：整行跳过（装饰器等于关掉，正文也不含它）
            if (escaped && !fallbacked) continue
            val candidate = if (escaped) line.substring(1) else line
            if (isKnown(candidate)) {
                decorators += candidate
                fallbacked = false
            } else {
                fallbacked = true
            }
        }
        return decorators to newContent
    }

    private fun isKnown(data: String): Boolean {
        val value = if (data.startsWith("@@@")) data.substring(1) else data
        return KNOWN.any { value.startsWith(it) }
    }
}
