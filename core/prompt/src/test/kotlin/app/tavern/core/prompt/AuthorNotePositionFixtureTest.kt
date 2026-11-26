package app.tavern.core.prompt

import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 作者注的两种"不插进历史"的位置（原版 `extension_prompt_types`）：
 * - `IN_PROMPT`（0）：紧跟主提示词之后（夹具 `P1-7-an-pos-0`）；
 * - `BEFORE_PROMPT`（2）：主提示词**之前**（夹具 `P1-7-an-pos-2`）。
 *
 * 默认的 `IN_CHAT`（1，按深度插进历史）在 `AuthorNoteFixtureTest` 里。
 */
class AuthorNotePositionFixtureTest {
    private val history = listOf(ChatMessage(role = ChatRole.Assistant, content = "FIRST-OMEGA"))

    @Test
    fun `IN_PROMPT 紧跟主提示词`() {
        val fixture = Fixtures.load("P1-7-an-pos-0")
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = fixture.userName,
            history = history,
            authorNote = AuthorNote(text = "AN-DELTA 正文", depth = 2, position = AuthorNote.IN_PROMPT),
        )
        assertEquals(fixture.messages, actual)
        assertEquals("AN-DELTA 正文", actual[1].content)
    }

    @Test
    fun `BEFORE_PROMPT 在主提示词之前`() {
        val fixture = Fixtures.load("P1-7-an-pos-2")
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = fixture.userName,
            history = history,
            authorNote = AuthorNote(text = "AN-DELTA 正文", depth = 2, position = AuthorNote.BEFORE_PROMPT),
        )
        assertEquals(fixture.messages, actual)
        assertEquals("AN-DELTA 正文", actual.first().content)
    }
}
