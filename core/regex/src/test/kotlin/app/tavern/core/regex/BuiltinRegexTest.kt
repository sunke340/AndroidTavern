package app.tavern.core.regex

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 内置显示规则的行为：包裹标签清洗、开关语义、以及几个容易误伤的写法。
 *
 * 强调（斜体/粗体）的"是否与原版一致"由 [InlineMarkupFixtureTest] 断言（夹具来自原版 showdown），
 * 这里只钉技术边界：关掉就完全不跑、白名单之外不动、行首列表不被当成斜体。
 */
class BuiltinRegexTest {

    private val engine = RegexEngine()

    private fun display(input: String, disabled: Set<String> = emptySet()): String =
        engine.applyAll(
            scripts = BuiltinRegex.rules(disabled),
            input = input,
            placement = RegexPlacement.AiOutput,
        )

    @Test
    fun `白名单包裹标签被清掉`() {
        assertEquals("你好", display("你好</content>"))
        assertEquals("A B", display("<content>A B</output>"))
        assertEquals("X", display("<Message>X</Message>"))
    }

    @Test
    fun `空白标签也清掉（单行的裸斜杠）`() {
        // 模型常把 `</>` 当分隔符写在段落之间：它没有标签名，原来那条"必须有标签名"的规则匹配不到
        // （用户 2026-11-25 第 4 条）。
        assertEquals("你好", display("你好</>"))
        assertEquals("AB", display("A</>B"))
        assertEquals("AB", display("A<>B"))
        // 标签独占一行时**连这一行一起删**（用户 2026-11-25：空行也要删）
        assertEquals("第一段\n第二段", display("第一段\n</>\n第二段"))
        // 行内出现就只删标签本身
        assertEquals("第一段\n第二段", display("第一段\n</>第二段"))
    }

    @Test
    fun `整行只有标记时连行一起删`() {
        assertEquals("A\nB", display("A\n</content>\nB"))
        assertEquals("A\nB", display("A\n<output>\nB"))
        // 前后有空白也算"整行只有标记"
        assertEquals("A\nB", display("A\n  </message>  \nB"))
    }

    @Test
    fun `别的空行不动`() {
        // 正文自己的分段（连续空行）是内容，不许一起吃掉
        assertEquals("A\n\nB", display("A\n\nB"))
        assertEquals("A\n\nB", display("A\n</>\n\nB"))
    }

    @Test
    fun `状态栏占位符被清掉`() {
        // MVU / 酒馆助手那套变量卡的占位符：行内、整行两种都清（用户 2026-11-25）
        assertEquals("正文", display("<StatusPlaceHolderImpl/>正文"))
        assertEquals("A\nB", display("A\n<StatusPlaceHolderImpl/>\nB"))
        assertEquals("正文", display("正文<StatusPlaceHolderImpl/>"))
    }

    @Test
    fun `白名单之外的标签不动`() {
        assertEquals("<foo>bar</foo>", display("<foo>bar</foo>"))
        assertEquals("<div>x</div>", display("<div>x</div>"))
        // 只是少了标签名的那两种要清；带名字的仍按白名单判断
        assertEquals("<span></span>", display("<span></span>"))
    }

    @Test
    fun `关掉清理标签后原文不变`() {
        assertEquals("你好</content>", display("你好</content>", setOf(BuiltinRegex.CLEAN_TAGS)))
    }

    @Test
    fun `关掉强调后星号与下划线原样保留`() {
        val raw = "*笑* 和 **粗** 和 _点头_"
        assertEquals(raw, display(raw, setOf(BuiltinRegex.EMPHASIS)))
    }

    @Test
    fun `行首星号列表不会被当成斜体`() {
        assertEquals("* 第一项", display("* 第一项"))
    }

    @Test
    fun `转义的星号不参与强调`() {
        assertEquals("\\*不是斜体\\*", display("\\*不是斜体\\*"))
    }

    @Test
    fun `两个开关都关掉时等于不跑内置规则`() {
        val raw = "</content>*笑*"
        assertEquals(raw, display(raw, setOf(BuiltinRegex.CLEAN_TAGS, BuiltinRegex.EMPHASIS)))
    }

    @Test
    fun `内置规则只作用于模型输出`() {
        val raw = "*笑*"
        val userInput = engine.applyAll(
            scripts = BuiltinRegex.rules(emptySet()),
            input = raw,
            placement = RegexPlacement.UserInput,
        )
        assertEquals(raw, userInput)
    }
}
