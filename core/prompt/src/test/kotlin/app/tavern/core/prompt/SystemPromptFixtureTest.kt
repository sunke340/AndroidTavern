package app.tavern.core.prompt

import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 卡里的 `system_prompt` / `post_history_instructions` 顶替预设对应段。
 *
 * 夹具 `P0-5-sysprompt` 是原版对一张带这两个字段的合成卡的**真实请求**：
 * main 变成 `SYSPROMPT-OMEGA for SysPrompt`（宏被替换），jailbreak 变成
 * `PHI-DELTA stay in character` 并排在聊天历史之后（`openai.js:1498` / `:1507`）。
 */
class SystemPromptFixtureTest {

    private val card = Fixtures.card("P0-5-sysprompt.png")
    private val fixture = Fixtures.load("P0-5-sysprompt")
    private val history = listOf(ChatMessage(role = ChatRole.Assistant, content = "FIRST-SYS"))

    @Test
    fun `卡里的两段指令顶替预设模板且顺序与原版一致`() {
        val actual = PromptBuilder().build(card, fixture.userName, history)

        if (actual != fixture.messages) {
            throw AssertionError(
                buildString {
                    appendLine("与夹具不一致：actual=${actual.size} expected=${fixture.messages.size}")
                    for (i in 0 until maxOf(actual.size, fixture.messages.size)) {
                        val a = actual.getOrNull(i)
                        val e = fixture.messages.getOrNull(i)
                        if (a != e) appendLine("--- [$i] actual=${a?.content?.take(120)} expected=${e?.content?.take(120)}")
                    }
                },
            )
        }
        assertEquals("SYSPROMPT-OMEGA for SysPrompt", actual.first().content)
        assertEquals("PHI-DELTA stay in character", actual.last().content)
    }
}
