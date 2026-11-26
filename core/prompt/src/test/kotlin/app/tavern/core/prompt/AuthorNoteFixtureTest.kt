package app.tavern.core.prompt

import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1-7 作者注：原版把它**插进聊天历史**（不是在系统提示里固定一段）。
 *
 * 两份夹具都是 2026-11-27 从原版 1.19.1 实拍的（`--an-text/--an-depth/--an-interval`）：
 * - `P1-7-an-prompt`：历史只有 1 条，depth=2 → 落在 `[Start a new Chat]` 之后、那条消息之前
 *   （块长 3，3-2=1 → 标记之后第一格，即"最多退到标记之后"）；
 * - `P1-7-an-depth-2`：历史有 4 条（2 轮对话），depth=2 → 后面正好留 2 条消息。
 */
class AuthorNoteFixtureTest {
    private val note = AuthorNote(text = "AN-DELTA 这段是作者注。", depth = 2)

    @Test
    fun `历史很短时作者注落在标记之后（不能跑到标记前面）`() {
        val fixture = Fixtures.load("P1-7-an-prompt")
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = fixture.userName,
            history = listOf(ChatMessage(role = ChatRole.Assistant, content = "FIRST-OMEGA")),
            authorNote = note,
        )
        assertEquals(fixture.messages, actual)
    }

    @Test
    fun `深度按"后面留几条"算`() {
        val fixture = Fixtures.load("P1-7-an-depth-2")
        val history = listOf(
            ChatMessage(role = ChatRole.Assistant, content = "FIRST-OMEGA"),
            ChatMessage(role = ChatRole.User, content = "你好"),
            ChatMessage(role = ChatRole.Assistant, content = "ok"),
            ChatMessage(role = ChatRole.User, content = "再来一句"),
        )
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = fixture.userName,
            history = history,
            authorNote = note,
        )
        assertEquals(fixture.messages, actual)
    }

    @Test
    fun `间隔不到就不插`() {
        val note = AuthorNote(text = "x", interval = 3)
        assertTrue(note.activeAt(3))
        // interval = 1 是原版特判：每轮都插，哪怕还没有用户消息
        assertTrue(AuthorNote(text = "x").activeAt(0))
        assertFalse(note.activeAt(1))
        assertFalse(note.activeAt(2))
        assertFalse(note.activeAt(0))
        assertFalse(AuthorNote(text = "  ").activeAt(1))
    }
}
