package app.tavern.core.regex

/**
 * 正则脚本执行器。逐条对照原版 `runRegexScript` / `getRegexedString`
 * （`public/scripts/extensions/regex/engine.js:337-465`）。
 *
 * 三个容易踩的点，这里都按原版来：
 * 1. **没有 `g` 标志只替换第一次**（JS `String.replace(regex, fn)` 的语义）；
 * 2. `$1` / `$<name>` / `{{match}}` 是**替换串里的**语法，且取到的捕获组要先按 `trimStrings` 过滤；
 * 3. `markdownOnly` / `promptOnly` 决定"哪一侧"生效：两个都没勾的脚本**只在既非 Markdown
 *    也非提示词**时跑（也就是聊天原文），不是"两边都跑"。
 *
 * 宏替换（`{{char}}` / `{{user}}` …）通过 [substitute] 注入，模块本身不认识宏。
 */
public class RegexEngine(
    private val substitute: (String) -> String = { it },
) {

    /**
     * 按顺序跑所有匹配 [placement] 的脚本。
     *
     * @param isMarkdown 这次是给"渲染后的 Markdown 显示"用的
     * @param isPrompt 这次是给"发出去的提示词"用的
     * @param depth 消息深度（原版用它过滤 `minDepth` / `maxDepth`）
     */
    public fun applyAll(
        scripts: List<RegexScript>,
        input: String,
        placement: RegexPlacement,
        isMarkdown: Boolean = false,
        isPrompt: Boolean = false,
        isEdit: Boolean = false,
        depth: Int? = null,
    ): String {
        if (input.isEmpty()) return input
        var result = input

        for (script in scripts) {
            val sideMatches =
                (script.markdownOnly && isMarkdown) ||
                    (script.promptOnly && isPrompt) ||
                    (!script.markdownOnly && !script.promptOnly && !isMarkdown && !isPrompt)
            if (!sideMatches) continue

            if (isEdit && !script.runOnEdit) continue

            if (depth != null) {
                val min = script.minDepth
                if (min != null && min >= -1 && depth < min) continue
                val max = script.maxDepth
                if (max != null && max >= 0 && depth > max) continue
            }

            if (placement !in script.placement) continue
            result = run(script, result)
        }

        return result
    }

    /** 跑单条脚本。 */
    public fun run(script: RegexScript, input: String): String {
        if (script.disabled || script.findRegex.isEmpty() || input.isEmpty()) return input

        val pattern = prepareFindRegex(script)
        val regex = compile(pattern) ?: return input
        val global = pattern.substringAfterLast('/', "").contains('g')

        // 注意：Kotlin 的 String.replace(regex, replacement) 会把 `$0` 当捕获组引用，
        // 所以这里必须用 Regex.escapeReplacement 把它变成字面量 `$0`，交给下面的展开逻辑。
        val preparedReplace = script.replaceString.replace(MATCH_PLACEHOLDER, Regex.escapeReplacement("\$0"))

        // stdlib 的 replace 没有"只替换前 N 次"的重载，所以自己走一遍 findAll：
        // 没有 g 标志时只替换第一次，与原版 JS 的 String.replace 一致。
        val out = StringBuilder()
        var last = 0
        var done = 0
        for (match in regex.findAll(input)) {
            if (!global && done >= 1) break
            out.append(input, last, match.range.first)
            out.append(expandReplacement(preparedReplace, match, script))
            last = match.range.last + 1
            done++
        }
        if (done == 0) return input
        out.append(input, last, input.length)
        return out.toString()
    }

    /** `substituteRegex`：0 原样、1 先宏替换、2 宏替换后转义成字面量。 */
    private fun prepareFindRegex(script: RegexScript): String = when (script.substituteRegex) {
        1 -> substitute(script.findRegex)
        2 -> escapeRegex(substitute(script.findRegex))
        else -> script.findRegex
    }

    /**
     * 把 JS 那套替换串展开：`$n` / `$<name>` 取捕获组（先过滤 `trimStrings`），
     * `{{match}}` 已在上面变成 `$0`；其余字符原样保留，最后做一次宏替换。
     */
    private fun expandReplacement(template: String, match: MatchResult, script: RegexScript): String {
        val out = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch == '$' && i + 1 < template.length) {
                val next = template[i + 1]
                if (next.isDigit()) {
                    var end = i + 1
                    while (end < template.length && template[end].isDigit()) end++
                    val index = template.substring(i + 1, end).toIntOrNull() ?: 0
                    val group = match.groupValues.getOrNull(index).orEmpty()
                    out.append(filterTrimStrings(group, script))
                    i = end
                    continue
                }
                if (next == '<') {
                    val close = template.indexOf('>', i + 2)
                    if (close > 0) {
                        val name = template.substring(i + 2, close)
                        val group = match.groups[name]?.value.orEmpty()
                        out.append(filterTrimStrings(group, script))
                        i = close + 1
                        continue
                    }
                }
            }
            out.append(ch)
            i++
        }
        return substitute(out.toString())
    }

    /** 原版 `filterString()`：把 `trimStrings` 里的子串从捕获组里删掉。 */
    private fun filterTrimStrings(value: String, script: RegexScript): String {
        var result = value
        for (trim in script.trimStrings) {
            if (trim.isEmpty()) continue
            result = result.replace(substitute(trim), "")
        }
        return result
    }

    private fun compile(pattern: String): Regex? {
        val match = SLASH_REGEX.matchEntire(pattern) ?: return null
        var body = match.groupValues[1]
        val flags = match.groupValues[2]
        // 未转义的斜杠当作非法（与原版 parseRegexFromString 一致）
        if (body.contains(UNESCAPED_SLASH)) return null
        body = body.replace("\\/", "/")

        val options = mutableSetOf<RegexOption>()
        if ('i' in flags) options += RegexOption.IGNORE_CASE
        if ('m' in flags) options += RegexOption.MULTILINE
        if ('s' in flags) options += RegexOption.DOT_MATCHES_ALL
        return runCatching { Regex(body, options) }.getOrNull()
    }

    private fun escapeRegex(text: String): String = Regex.escape(text)

    private companion object {
        /** 原版把 `{{match}}`（不分大小写）换成 `$0`。 */
        val MATCH_PLACEHOLDER = Regex("\\{\\{match\\}\\}", RegexOption.IGNORE_CASE)
        val SLASH_REGEX = Regex("^/([\\w\\W]+?)/([gimsuy]*)$")
        val UNESCAPED_SLASH = Regex("(^|[^\\\\])/")
    }
}
