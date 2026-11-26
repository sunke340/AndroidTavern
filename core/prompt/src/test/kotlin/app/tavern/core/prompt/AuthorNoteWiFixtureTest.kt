package app.tavern.core.prompt

import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 世界书里 `position = ANTop / ANBottom` 的条目会**拼进作者注那一条**（原版 `world-info.js`
 * 的 `ANWithWI`：`top… \n 正文 \n bottom…`，只削掉首尾各一个换行），然后整体按 interval / 深度注入。
 *
 * 夹具 `P1-7-an-with-wi` 是原版 1.19.1 的实拍请求。
 */
class AuthorNoteWiFixtureTest {
    private val fixture = Fixtures.load("P1-7-an-with-wi")

    @Test
    fun `顶部与底部条目拼成一条注入文本`() {
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = fixture.userName,
            history = listOf(ChatMessage(role = ChatRole.Assistant, content = "FIRST-OMEGA")),
            authorNote = AuthorNote(text = "AN-DELTA 正文", depth = 2),
            authorNoteTop = listOf("ANTOP-ALPHA 作者注顶部"),
            authorNoteBottom = listOf("ANBOT-BETA 作者注底部"),
        )
        assertEquals(fixture.messages, actual)
        assertEquals(
            "ANTOP-ALPHA 作者注顶部\nAN-DELTA 正文\nANBOT-BETA 作者注底部",
            actual[8].content,
        )
    }

    @Test
    fun `正文为空时首尾各削一个换行，中间的空白留着（照原版）`() {
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = "User",
            authorNote = AuthorNote(text = "", depth = 1),
            authorNoteTop = listOf("TOP"),
            authorNoteBottom = listOf("BOTTOM"),
        )
        val injected = actual.firstOrNull { it.content.startsWith("TOP") }
        assertEquals("TOP\n\nBOTTOM", injected?.content)
    }
}
