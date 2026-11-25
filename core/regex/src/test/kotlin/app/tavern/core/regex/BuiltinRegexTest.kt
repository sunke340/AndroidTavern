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
    fun `白名单之外的标签不动`() {
        assertEquals("<foo>bar</foo>", display("<foo>bar</foo>"))
        assertEquals("<div>x</div>", display("<div>x</div>"))
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
