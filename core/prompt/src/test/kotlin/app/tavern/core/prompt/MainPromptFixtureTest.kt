package app.tavern.core.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 第一条真正的兼容性断言：我们拼出来的东西，必须与 [fixtures/expected/P0-5-prompt-basic.json]
 * ——即原版 1.19.1 实际发给 API 的内容——逐字相等。
 */
class MainPromptFixtureTest {
    private val fixture = Fixtures.load("P0-5-prompt-basic")

    @Test
    fun `契约前提：夹具是四段 system 加一段 assistant`() {
        assertEquals(5, fixture.messages.size, "夹具消息数变了，先确认原版是否升级")

        val roles = fixture.messages.map { it.role }
        assertEquals(
            listOf(ChatRole.System, ChatRole.System, ChatRole.System, ChatRole.System, ChatRole.Assistant),
            roles,
        )
    }

    @Test
    fun `main prompt 与原版逐字一致`() {
        val expected = fixture.messages.first()
        val actual = PromptBuilder().mainPrompt(
            characterName = fixture.characterName,
            userName = fixture.userName,
        )

        assertEquals(expected.role, actual.role)
        assertEquals(expected.content, actual.content)
    }

    @Test
    fun `new chat 标记插在角色定义之后、聊天历史之前`() {
        val index = fixture.messages.indexOfFirst { it.content == SillyTavernDefaults.NEW_CHAT }
        assertTrue(index in 1 until fixture.messages.lastIndex, "标记位置异常：$index")

        assertEquals(PromptBuilder().newChatMarker(), fixture.messages[index])
    }

    @Test
    fun `最后一条是角色卡的开场白且角色为 assistant`() {
        val last = fixture.messages.last()
        assertEquals(ChatRole.Assistant, last.role)
        assertTrue(
            last.content.startsWith("*You wake with a start"),
            "开场白变了，原版默认卡可能已更新：${last.content.take(60)}",
        )
    }
}
