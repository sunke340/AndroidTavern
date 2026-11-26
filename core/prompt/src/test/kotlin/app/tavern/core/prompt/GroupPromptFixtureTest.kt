package app.tavern.core.prompt

import app.tavern.core.model.ChatMessage
import app.tavern.core.model.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 群聊的请求形状（P2-1）。原版四份夹具的形状**完全一样**，只有"谁说话"不同：
 * `char_name` = 说话人、角色三段只取那张卡、历史块首换成员清单、历史带 `名字: ` 前缀、
 * 示例对话**保留**名字前缀、末尾一条 `[Write the next reply only as X.]`。
 *
 * 历史的 `名字: ` 前缀由调用方拼好（应用侧知道每条是谁说的；`ChatMessage` 刻意只有 role/content）。
 */
class GroupPromptFixtureTest {
    private val members = listOf("Synthetic", "SysPrompt")

    @Test
    fun `natural：说话人是 Synthetic，示例保留名字前缀`() {
        val fixture = Fixtures.load("P2-1-group-natural")
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = fixture.userName,
            history = listOf(
                ChatMessage(role = ChatRole.Assistant, content = "Synthetic: FIRST-OMEGA"),
                ChatMessage(role = ChatRole.Assistant, content = "SysPrompt: FIRST-SYS"),
            ),
            group = GroupContext(members = members, speaker = "Synthetic"),
        )
        assertEquals(fixture.messages, actual)
        assertEquals("[Start a new group chat. Group members: Synthetic, SysPrompt]", actual[7].content)
        assertEquals("[Write the next reply only as Synthetic.]", actual.last().content)
    }

    @Test
    fun `list：说话人换人，角色三段跟着换那张卡`() {
        val fixture = Fixtures.load("P2-1-group-list")
        val actual = PromptBuilder().build(
            character = Fixtures.card("P0-5-sysprompt.png"),
            userName = fixture.userName,
            history = listOf(
                ChatMessage(role = ChatRole.Assistant, content = "Synthetic: FIRST-OMEGA"),
                ChatMessage(role = ChatRole.Assistant, content = "SysPrompt: FIRST-SYS"),
                ChatMessage(role = ChatRole.Assistant, content = "Synthetic: ok"),
            ),
            group = GroupContext(members = members, speaker = "SysPrompt"),
        )
        assertEquals(fixture.messages, actual)
        // 说话人那张卡有 system_prompt → 顶替 main（和单聊同一条规则）
        assertEquals("SYSPROMPT-OMEGA for SysPrompt", actual.first().content)
    }

    @Test
    fun `单聊不受影响：块首还是 Start a new Chat，示例仍剥名字`() {
        val actual = PromptBuilder().build(
            character = syntheticCard,
            userName = "User",
            history = listOf(ChatMessage(role = ChatRole.Assistant, content = "FIRST-OMEGA")),
        )
        assertEquals("[Start a new Chat]", actual[7].content)
        assertEquals("EXAMPLE-DELTA-user line", actual[5].content)
    }
}
