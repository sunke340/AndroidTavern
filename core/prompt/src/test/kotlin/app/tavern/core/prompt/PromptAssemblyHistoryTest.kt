package app.tavern.core.prompt

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 多轮历史怎么进提示词。
 *
 * 夹具录制方式：像用户一样把两句话发进原版（中间助手回复由假 SSE 统一给 `ok`），
 * 截获最后一次生成请求。所以历史里既有真实用户消息，也有"模型回复"。
 */
class PromptAssemblyHistoryTest {
    private val fixture = Fixtures.load("P0-5-prompt-history")

    private val history = listOf(
        ChatMessage(role = ChatRole.Assistant, content = "FIRST-OMEGA"),
        ChatMessage(role = ChatRole.User, content = "USER-ONE first question"),
        ChatMessage(role = ChatRole.Assistant, content = "ok"),
        ChatMessage(role = ChatRole.User, content = "USER-THREE second question"),
    )

    @Test
    fun `多轮历史逐条相等`() {
        val actual = PromptBuilder().build(syntheticCard, fixture.userName, history)

        assertEquals(fixture.messages, actual, diffReport(actual, fixture.messages))
    }

    @Test
    fun `历史里的 CRLF 会被去掉`() {
        val withCrlf = listOf(ChatMessage(role = ChatRole.User, content = "line1\r\nline2"))
        val built = PromptBuilder().build(syntheticCard, "User", withCrlf)

        assertEquals("line1\nline2", built.last().content)
    }

    @Test
    fun `new chat 标记始终在历史之前`() {
        val markerIndex = fixture.messages.indexOfFirst { it.content == SillyTavernDefaults.NEW_CHAT }
        val firstHistoryIndex = fixture.messages.indexOfFirst { it.content == "FIRST-OMEGA" }

        assertEquals(true, markerIndex in 0 until firstHistoryIndex, "标记位置：$markerIndex，历史起点：$firstHistoryIndex")
    }

    private fun diffReport(actual: List<ChatMessage>, expected: List<ChatMessage>): String = buildString {
        appendLine("actual=${actual.size} expected=${expected.size}")
        for (i in 0 until maxOf(actual.size, expected.size)) {
            val a = actual.getOrNull(i)
            val e = expected.getOrNull(i)
            if (a != e) {
                appendLine("--- [$i] actual=$a")
                appendLine("         expected=$e")
            }
        }
    }
}
