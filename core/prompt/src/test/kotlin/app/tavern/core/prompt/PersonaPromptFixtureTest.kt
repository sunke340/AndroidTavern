package app.tavern.core.prompt

import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P1-8 人设：原版把「人设描述」当成**独立的一条 system 消息**，位置由 `prompt_order` 里
 * 的 `personaDescription` 决定（默认预设里在 `worldInfoBefore` 之后、`charDescription` 之前）。
 *
 * 夹具 `P1-8-persona-prompt` 是 2026-11-27 从原版 1.19.1 实拍的请求体（用户名「旅行者」）。
 * 这里复现同样的输入，断言整条消息链逐条逐字相等 —— 位置、role、内容三者只要有一个偏了，
 * 这个测试就会指出第几条不一致。
 */
class PersonaPromptFixtureTest {
    private val fixture = Fixtures.load("P1-8-persona-prompt")

    /** 录制时设进去的人设描述（见 `tools/fixture/capture.mjs --persona-desc`）。 */
    private val persona = "PERSONA-ALPHA 是一名记录海图的抄写员，说话简短。"

    @Test
    fun `人设描述落在 personaDescription 那一格且与原版逐条相等`() {
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = fixture.userName,
            history = listOf(ChatMessage(role = ChatRole.Assistant, content = "FIRST-OMEGA")),
            personaDescription = persona,
        )

        if (actual != fixture.messages) {
            val report = buildString {
                appendLine("消息数与夹具不一致：actual=${actual.size} expected=${fixture.messages.size}")
                for (i in 0 until maxOf(actual.size, fixture.messages.size)) {
                    val a = actual.getOrNull(i)
                    val e = fixture.messages.getOrNull(i)
                    if (a != e) {
                        appendLine("--- [$i] 不一致")
                        appendLine("  actual  : ${a?.role} ${a?.content}")
                        appendLine("  expected: ${e?.role} ${e?.content}")
                    }
                }
            }
            throw AssertionError(report)
        }
    }

    @Test
    fun `没选人设时不产生那一段（默认预设结构不变）`() {
        val noPersona = PromptBuilder().build(
            character = syntheticCard,
            userName = "User",
            history = listOf(ChatMessage(role = ChatRole.Assistant, content = "FIRST-OMEGA")),
        )
        val base = Fixtures.load("P0-5-prompt-basic")
        assertEquals(base.messages, noPersona)
    }
}
