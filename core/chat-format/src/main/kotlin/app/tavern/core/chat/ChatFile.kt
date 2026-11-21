package app.tavern.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 原版聊天文件（`.jsonl`）。**逐行一个 JSON 对象，第一行是表头**：
 *
 * ```
 * {"chat_metadata":{...},"user_name":"unused","character_name":"unused"}
 * {"name":"角色","is_user":false,"is_system":false,"send_date":"…","mes":"…","swipes":["…"],"swipe_id":0,…}
 * {"name":"User","is_user":true,"is_system":false,"send_date":"…","mes":"…","extra":{…}}
 * ```
 *
 * 三条照抄原版的行为（都能在 `src/endpoints/chats.js` 与 `public/script.js` 里看到）：
 *
 * 1. **首行无条件当表头**，哪怕它长得像消息。原版是 `data.shift()`，不做校验。
 * 2. **解析不了的行直接丢**，不报错——`getChatData` 里 `tryParse(line)).filter(x => x)`。
 * 3. **写出时不带结尾换行**：`chatData.map(JSON.stringify).join('\n')`。
 *
 * 表头原样保留（`header`），不认识的键（`tainted`、`timedWorldInfo`、别的客户端塞的）都不会丢。
 */
public class ChatFile internal constructor(
    public val header: JsonObject,
    public val messages: List<ChatMessage>,
) {

    /** 表头里的 `chat_metadata`；缺省是空对象。 */
    public val metadata: JsonObject
        get() = header["chat_metadata"] as? JsonObject ?: JsonObject(emptyMap())

    public fun withMessages(messages: List<ChatMessage>): ChatFile = ChatFile(header, messages)

    /** 换掉 metadata，表头里的其他键保持原样。 */
    public fun withMetadata(metadata: JsonObject): ChatFile =
        ChatFile(JsonObject(header + ("chat_metadata" to metadata)), messages)

    /** 写回成 `.jsonl` 文本（不带结尾换行，和原版一致）。 */
    public fun serialize(): String =
        (listOf(header) + messages.map { it.raw }).joinToString("\n") { it.toString() }

    public companion object {

        private val emptyMetadata = JsonObject(emptyMap())

        /** 空聊天：只有表头。原版新建聊天时就是这个形状。 */
        public fun empty(): ChatFile = ChatFile(header(emptyMetadata), emptyList())

        public fun of(
            messages: List<ChatMessage>,
            metadata: JsonObject = emptyMetadata,
        ): ChatFile = ChatFile(header(metadata), messages)

        private fun header(metadata: JsonObject): JsonObject = JsonObject(
            linkedMapOf(
                "chat_metadata" to metadata,
                // 原版写的就是这两个占位值，不是真名（真名在角色卡里）
                "user_name" to JsonPrimitive("unused"),
                "character_name" to JsonPrimitive("unused"),
            ),
        )

        /**
         * 读取。容忍空行、结尾换行、单行文件、以及中间某行坏掉。
         *
         * 单行文件 = 只有表头 = 空聊天，这和原版一样：那一行被 `shift()` 掉了。
         */
        public fun parse(text: String): ChatFile {
            val objects = text.split('\n')
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line -> runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject }
                .toList()

            val header = objects.firstOrNull() ?: header(emptyMetadata)
            return ChatFile(header = header, messages = objects.drop(1).map { ChatMessage(it) })
        }
    }
}
