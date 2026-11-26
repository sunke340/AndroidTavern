package app.tavern.core.model

/**
 * 「揭示节奏」用到的纯文本工具（口径见 `docs/plan-reveal-pacing.md`）。
 *
 * 放在 `core/model` 是因为它**只有 String 运算**、能进纯 JVM 单测 —— 而这两件恰恰是最容易写错的：
 * 代理对（surrogate pair）会被 UTF-16 索引切成两半，切错了就是豆腐块。
 *
 * 单位一律 **Unicode 码点**：`String.length` 是 UTF-16 单元（一个 emoji 算 2），拿它做速率、
 * 比较、剩余都会和"字"对不上。`length` 只允许出现在 `codePointCount` / `offsetByCodePoints` 的入参位置。
 */

/** 取前 [codePoints] 个码点；越界时返回整串，负数返回空串。永远落在完整码点边界上。 */
public fun takeCodePoints(text: String, codePoints: Int): String {
    if (codePoints <= 0) return ""
    val total = text.codePointCount(0, text.length)
    if (codePoints >= total) return text
    return text.substring(0, text.offsetByCodePoints(0, codePoints))
}

/**
 * 增量码点计数：**只数新增后缀**，不做全文扫描。
 *
 * 全文重数只发生在：① 首次取文本（[prevText] 为 null）；② 非追加变化（换正文）。
 *
 * 边界：上一段末尾是高代理、新段第一个是低代理时，这个代理对**已经被 prevCount 计入过**
 * （增量里它是"一个已存在的码点"），所以后缀计数要减 1，否则同一个 emoji 会被数两次。
 */
public fun countCodePointsIncrementally(newText: String, prevText: String?, prevCount: Int): Int {
    if (prevText != null && newText === prevText) return prevCount
    if (prevText != null && newText.length > prevText.length && newText.startsWith(prevText)) {
        val suffixStart = prevText.length
        var add = newText.codePointCount(suffixStart, newText.length)
        if (suffixStart > 0 &&
            newText[suffixStart - 1].isHighSurrogate() &&
            newText[suffixStart].isLowSurrogate()
        ) {
            add -= 1
        }
        return prevCount + add
    }
    return newText.codePointCount(0, newText.length)
}
