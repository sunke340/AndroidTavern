package app.tavern.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 开场白（`first_mes`）的原版行为，用两份**原版实际产物**交叉验证：
 *
 * - `fixtures/chats/P0-8-first-mes-chat.json`：原版新建聊天后内存里的 chat 数组
 * - `fixtures/expected/P0-8-first-mes-prompt.json`：同一时刻 `Generate()` 发出去的请求体
 *
 * 两份都是 `tools/fixture/capture.mjs --dump-chat` 录的，不是手写期望值。
 */
class FirstMesFixtureTest {

    private val repoRoot: String = System.getProperty("repo.root") ?: error("缺少 repo.root")

    private fun json(path: String) =
        Json.parseToJsonElement(File(repoRoot, path).readText(Charsets.UTF_8))

    @Test
    fun `原版新建聊天的第一条就是角色开场白`() {
        val chat = json("fixtures/chats/P0-8-first-mes-chat.json") as JsonArray
        assertTrue(chat.isNotEmpty(), "录制的聊天不该为空")

        val first = chat.first().jsonObject
        assertFalse(first.getValue("is_user").jsonPrimitive.boolean, "开场白不是用户消息")
        assertEquals("Seraphina", first.getValue("name").jsonPrimitive.content)
        assertTrue(
            first.getValue("mes").jsonPrimitive.content.length > 80,
            "开场白正文应该就是卡里的 first_mes，不该是空串",
        )

        Unit
    }

    @Test
    fun `开场白作为 assistant 进提示词历史`() {
        val chat = json("fixtures/chats/P0-8-first-mes-chat.json") as JsonArray
        val greeting = chat.first().jsonObject.getValue("mes").jsonPrimitive.content

        val messages = (json("fixtures/expected/P0-8-first-mes-prompt.json") as JsonObject)
            .getValue("request").jsonObject.getValue("messages") as JsonArray

        val last = messages.last().jsonObject
        assertEquals("assistant", last.getValue("role").jsonPrimitive.content)
        assertEquals(greeting, last.getValue("content").jsonPrimitive.content)

        Unit
    }

    @Test
    fun `开场白能被转成 JSONL 再读回来`() {
        val chat = json("fixtures/chats/P0-8-first-mes-chat.json") as JsonArray
        val messages = chat.map { it.jsonObject }
        val greeting = messages.first().getValue("mes").jsonPrimitive.content

        val file = ChatFile(
            header = JsonObject(mapOf("chat_metadata" to JsonObject(emptyMap()))),
            messages = messages.map { ChatMessage(it) },
        )

        val parsed = ChatFile.parse(file.serialize())
        assertEquals(messages.size, parsed.messages.size)
        assertEquals(greeting, parsed.messages.first().text)
        assertFalse(parsed.messages.first().isUser)

        Unit
    }
}
