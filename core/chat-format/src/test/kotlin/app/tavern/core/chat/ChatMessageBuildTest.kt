package app.tavern.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 我们自己造的消息（导出、新建聊天时用）必须能被原版规则读回来。
 * 这里断言的是"结构自洽"：写出去再读回来，正文与角色不丢。
 */
class ChatMessageBuildTest {

    @Test
    fun `用户消息写出再读回来不丢内容`() {
        val message = ChatMessage.user("User", "你好啊", "2026-11-22T00:00:00.000Z")
        val chat = ChatFile.of(listOf(message))

        val parsed = ChatFile.parse(chat.serialize()).messages.single()
        assertEquals("你好啊", parsed.text)
        assertTrue(parsed.isUser)
        assertEquals("User", parsed.name)
        assertEquals("2026-11-22T00:00:00.000Z", parsed.sendDate)
        Unit
    }

    @Test
    fun `角色消息带 swipes 原版才能换一个`() {
        val message = ChatMessage.assistant("Synthetic", "在的", "2026-11-22T00:00:01.000Z")
        val parsed = ChatFile.parse(ChatFile.of(listOf(message)).serialize()).messages.single()

        assertEquals("在的", parsed.text)
        assertEquals(listOf("在的"), parsed.swipes)
        assertEquals(0, parsed.swipeId)
        // swipe_info 必须存在：原版按它记每条备选的生成时间，缺了会在换一个时出错
        assertTrue(message.raw.containsKey("swipe_info"))
        Unit
    }

    @Test
    fun `空 metadata 的表头与原版一致`() {
        val header = ChatFile.empty().serialize()
        assertEquals("""{"chat_metadata":{},"user_name":"unused","character_name":"unused"}""", header)
        Unit
    }

    @Test
    fun `多条 swipe 导出后原版还能换回其它版本`() {
        val message = ChatMessage.assistantWithSwipes(
            name = "Synthetic",
            swipes = listOf("第一版", "第二版", "第三版"),
            swipeId = 1,
            sendDate = "2026-11-25T00:00:00.000Z",
        )
        val parsed = ChatFile.parse(ChatFile.of(listOf(message)).serialize()).messages.single()

        assertEquals("第二版", parsed.text, "mes 应当是 swipe_id 指向的那条")
        assertEquals(1, parsed.swipeId)
        assertEquals(listOf("第一版", "第二版", "第三版"), parsed.swipes)
        assertEquals("第一版", parsed.withSwipe(0).text, "原版换一个要能拿到第一版")
        Unit
    }
}