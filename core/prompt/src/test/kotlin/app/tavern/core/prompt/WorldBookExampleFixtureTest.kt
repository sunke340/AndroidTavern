package app.tavern.core.prompt

import app.tavern.core.lorebook.LorebookScanResult
import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 世界书里 `position = EMTop / EMBottom`（5 / 6）的条目会**并进示例对话块**：
 * 每一块（世界书的每条 + 卡自己的 `mes_example`）都自带一个 `[Example Chat]` 标记，
 * 顶部的排在卡示例之前、底部的排在之后。
 *
 * 夹具 `P1-3-example-injection` 是原版 1.19.1 的实拍请求（两张入口条目都写成
 * `<START>\n{{user}}: …`，因为原版 `parseMesExamples` 以 `<START>` 分段并剥掉名字前缀）。
 */
class WorldBookExampleFixtureTest {
    private val fixture = Fixtures.load("P1-3-example-injection")

    @Test
    fun `顶部与底部示例各自成块，位置与原版一致`() {
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = fixture.userName,
            history = listOf(ChatMessage(role = ChatRole.Assistant, content = "FIRST-OMEGA")),
            worldInfo = LorebookScanResult(
                exampleTop = listOf("<START>\n{{user}}: EXTOP-ALPHA 示例开头"),
                exampleBottom = listOf("<START>\n{{char}}: EXBOT-BETA 示例结尾"),
            ),
        )
        assertEquals(fixture.messages, actual)
        val texts = actual.map { it.content }
        // 顶部在卡示例之前、底部在之后
        assert(texts.indexOf("EXTOP-ALPHA 示例开头") < texts.indexOf("EXAMPLE-DELTA-user line"))
        assert(texts.indexOf("EXBOT-BETA 示例结尾") > texts.indexOf("EXAMPLE-DELTA-char line"))
    }
}
