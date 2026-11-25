package app.tavern.core.regex

/** 一段带内联样式的文本（[InlineMarkup] 的输出）。 */
public data class InlineRun(
    public val text: String,
    public val italic: Boolean = false,
    public val bold: Boolean = false,
    /** `<br>` 变成的换行：此时 [text] 为空。 */
    public val lineBreak: Boolean = false,
)

/**
 * 把正则输出里的**内联标签**解析成带样式的片段。
 *
 * 只认这一小撮（原版 Markdown 渲染出来的就是这几种，内置正则也只产出 `<i>` / `<b>`）：
 * `<i>` `<em>` `<b>` `<strong>` `<br>`。**其他标签原样保留成文本** ——
 * 不做 HTML 渲染，也就不需要 WebView / 富文本控件。
 *
 * 为什么要这一层：正则只能替换文本，替换不出"斜体"；原版的路径是
 * "正则/Markdown → HTML → 渲染"。我们把 HTML 缩到最小的一撮，既能表达斜体/粗体，
 * 又顺带让用户自己导入的、输出这几个标签的 ST 正则脚本能正常显示。
 */
public object InlineMarkup {

    /** 标签名 → 样式操作。 */
    private enum class Op { ITALIC_ON, ITALIC_OFF, BOLD_ON, BOLD_OFF, BREAK }

    private fun opOf(tag: String): Op? = when (tag.lowercase().replace(" ", "")) {
        "i", "em" -> Op.ITALIC_ON
        "/i", "/em" -> Op.ITALIC_OFF
        "b", "strong" -> Op.BOLD_ON
        "/b", "/strong" -> Op.BOLD_OFF
        "br", "br/", "/br" -> Op.BREAK
        else -> null
    }

    public fun parse(input: String): List<InlineRun> {
        if (input.isEmpty()) return emptyList()
        val out = mutableListOf<InlineRun>()
        var italic = false
        var bold = false
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isEmpty()) return
            val run = InlineRun(buffer.toString(), italic = italic, bold = bold)
            val last = out.lastOrNull()
            // 相邻同样式合并，避免一个段落被拆成几百段
            if (last != null && !last.lineBreak &&
                last.italic == run.italic && last.bold == run.bold
            ) {
                out[out.lastIndex] = last.copy(text = last.text + run.text)
            } else {
                out += run
            }
            buffer.setLength(0)
        }

        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch != '<') {
                buffer.append(ch)
                i++
                continue
            }
            val close = input.indexOf('>', i + 1)
            if (close < 0) {
                // 没有闭合的尖括号：后面全是正文
                buffer.append(input, i, input.length)
                break
            }
            val tag = input.substring(i + 1, close)
            val op = opOf(tag)
            if (op == null) {
                // 不认识的标签：原样当正文（和现在"看到 <xxx>"的行为一致）
                buffer.append('<').append(tag).append('>')
                i = close + 1
                continue
            }
            flush()
            when (op) {
                Op.ITALIC_ON -> italic = true
                Op.ITALIC_OFF -> italic = false
                Op.BOLD_ON -> bold = true
                Op.BOLD_OFF -> bold = false
                // <br>：单独记一段换行（空文本 + lineBreak）
                Op.BREAK -> out += InlineRun("", lineBreak = true)
            }
            i = close + 1
        }
        flush()
        return out
    }

    /** 去掉标签后的纯文本（测试与"复制"这类场景用）。 */
    public fun plainText(input: String): String = buildString {
        for (run in parse(input)) {
            if (run.lineBreak) append('\n') else append(run.text)
        }
    }
}
