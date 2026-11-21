package app.tavern.core.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 兼容性的核心断言：我们拼出来的整条消息链，必须与 [fixtures/expected/P0-5-prompt-basic.json]
 * ——即原版 1.19.1 实际发给 API 的内容——逐条逐字相等。
 *
 * 夹具用的卡是 `fixtures/cards/P0-5-synthetic.png`（由 `tools/fixture/make-test-card.py` 生成），
 * 字段值带醒目标记，所以一旦哪个变换错了，diff 会直接指出来。
 */
class PromptAssemblyFixtureTest {
    private val fixture = Fixtures.load("P0-5-prompt-basic")

    private val definition = syntheticCard

    private val history = listOf(ChatMessage(role = ChatRole.Assistant, content = "FIRST-OMEGA"))

    @Test
    fun `整条链路与原版逐条相等`() {
        val actual = PromptBuilder().build(definition, fixture.userName, history)

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
    fun `空字段不会产生空消息`() {
        val bare = CharacterDefinition(name = "Synthetic")
        val actual = PromptBuilder().build(bare, "User")

        // 只有 main + new-chat 标记，没有任何空 system 消息
        assertEquals(2, actual.size)
        assertTrue(actual.all { it.content.isNotBlank() })
    }

    @Test
    fun `示例对话的名字前缀被剥掉且独立成条`() {
        val parsed = ExampleDialogue.parse(definition.mesExample, "Synthetic", "User")

        assertEquals(listOf("EXAMPLE-DELTA-user line", "EXAMPLE-DELTA-char line"), parsed)
    }

    @Test
    fun `第一行永远被丢掉（原版的怪癖）`() {
        // 原版假设第一行是 "<START>" 这类标题；即使没有标题，第一行也会消失
        val parsed = ExampleDialogue.parse("{{user}}: 这行会被丢掉\n{{char}}: 这行留着", "Synthetic", "User")

        assertEquals(listOf("这行留着"), parsed)
    }
}
