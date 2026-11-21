package app.tavern.core.chat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 拿**原版实际写出**的聊天文件当基准（`fixtures/chats/P0-7-original-multiturn.jsonl`，
 * 由原版 UI 经 playwright 录制，不是手写）。
 */
class ChatFileFixtureTest {

    private val repoRoot: String = System.getProperty("repo.root") ?: error("缺少 repo.root")
    private val fixture: String =
        File(repoRoot, "fixtures/chats/P0-7-original-multiturn.jsonl").readText(Charsets.UTF_8)

    @Test
    fun `原版文件读出来的结构正确`() {
        val chat = ChatFile.parse(fixture)

        assertEquals(5, chat.messages.size, "表头之外应有 5 条消息")
        assertEquals("Synthetic", chat.messages.first().name)
        assertFalse(chat.messages.first().isUser)
        assertEquals("FIRST-OMEGA", chat.messages.first().text)
        assertEquals(listOf("FIRST-OMEGA"), chat.messages.first().swipes)
        assertEquals(0, chat.messages.first().swipeId)

        val questions = chat.messages.filter { it.isUser }.map { it.text }
        assertEquals(listOf("USER-ONE first question", "USER-THREE second question"), questions)

        // metadata 里的不认识的键必须读得到（原版写的是 integrity / tainted / timedWorldInfo …）
        assertTrue(chat.metadata.containsKey("integrity"), "metadata 应保留 integrity")
        assertTrue(chat.metadata.containsKey("timedWorldInfo"))

        Unit
    }

    @Test
    fun `读进来再写出去逐字节相同`() {
        // 这就是"往返可迁移"的核心断言：不改内容时，重新序列化必须与原文件完全一致，
        // 否则用户在两个客户端之间来回搬就会看到莫名其妙的大 diff。
        assertEquals(fixture, ChatFile.parse(fixture).serialize())
        Unit
    }

    @Test
    fun `swipe 切换只改 mes 与 swipe_id`() {
        val two = ChatFile.parse(
            """
            {"chat_metadata":{}}
            {"name":"A","is_user":false,"mes":"第一版","swipes":["第一版","第二版"],"swipe_id":0}
            """.trimIndent(),
        )
        val message = two.messages.single()
        assertFalse(message.isUser)

        val switched = message.withSwipe(1)
        assertEquals("第二版", switched.text)
        assertEquals(1, switched.swipeId)
        // 原始 raw 不被破坏：换 swipe 得到的是新对象
        assertEquals("第一版", message.text)
        Unit
    }

    @Test
    fun `坏行被丢掉而不是让整个文件读不出来`() {
        val text = """
            {"chat_metadata":{}}
            {"name":"A","is_user":true,"mes":"好的"}
            这不是 JSON
            {"name":"A","is_user":false,"mes":"回一句"}
        """.trimIndent()

        val chat = ChatFile.parse(text)
        assertEquals(listOf("好的", "回一句"), chat.messages.map { it.text })

        Unit
    }

    @Test
    fun `空文件与只有表头的文件都算空聊天`() {
        assertEquals(0, ChatFile.parse("").messages.size)
        assertEquals(0, ChatFile.parse("""{"chat_metadata":{"integrity":"x"}}""").messages.size)
        // 空聊天写出去也只有一行
        assertEquals(1, ChatFile.empty().serialize().split('\n').size)
        Unit
    }
}
