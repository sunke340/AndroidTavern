package app.tavern.core.regex

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 内联标签解析：只认 `<i> <em> <b> <strong> <br>`，其余原样显示。
 *
 * 这是"正则替换 HTML、渲染层负责样式"那条路的接缝；不认识的标签必须保持原样，
 * 否则用户正文里的 XML 会被吃掉。
 */
class InlineMarkupTest {

    @Test
    fun `斜体与粗体标签被转成样式片段`() {
        val runs = InlineMarkup.parse("前<i>斜</i>后<b>粗</b>")
        assertEquals(
            listOf(
                InlineRun("前"),
                InlineRun("斜", italic = true),
                InlineRun("后"),
                InlineRun("粗", bold = true),
            ),
            runs,
        )
    }

    @Test
    fun `em 与 strong 等价于 i 与 b`() {
        val runs = InlineMarkup.parse("<em>a</em><strong>b</strong>")
        assertEquals(
            listOf(InlineRun("a", italic = true), InlineRun("b", bold = true)),
            runs,
        )
    }

    @Test
    fun `br 变成换行片段`() {
        val runs = InlineMarkup.parse("上<br>下")
        assertEquals(
            listOf(InlineRun("上"), InlineRun("", lineBreak = true), InlineRun("下")),
            runs,
        )
    }

    @Test
    fun `不认识的标签原样保留`() {
        assertEquals("<content>x</content>", InlineMarkup.plainText("<content>x</content>"))
    }

    @Test
    fun `没有闭合的尖括号按正文处理`() {
        assertEquals("a < b", InlineMarkup.plainText("a < b"))
    }

    @Test
    fun `嵌套与相邻同样式会合并`() {
        val runs = InlineMarkup.parse("<i>a<b>b</b>c</i>d")
        assertEquals(
            listOf(
                InlineRun("a", italic = true),
                InlineRun("b", italic = true, bold = true),
                InlineRun("c", italic = true),
                InlineRun("d"),
            ),
            runs,
        )
    }
}
