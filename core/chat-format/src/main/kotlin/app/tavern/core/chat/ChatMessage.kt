package app.tavern.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean

/**
 * 一条聊天消息。**原始 JSON 是真相**，取值只是视图——理由同角色卡：
 * 消息里有一堆我们不理解的字段（`extra` 里的推理文本、`swipe_info`、`gen_started`、
 * 各家前端的私有键），解析成强类型再写回就丢了。读进来什么样，写出去还得什么样。
 *
 * 字段语义照 `public/script.js`：`is_user` / `is_system` 是布尔，`mes` 是正文，
 * `swipes` 是同一位置的备选回复，`swipe_id` 指向当前那条。
 */
public class ChatMessage internal constructor(public val raw: JsonObject) {

    private fun string(key: String): String =
        (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun bool(key: String): Boolean =
        (raw[key] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() } ?: false

    public val name: String get() = string("name")
    public val isUser: Boolean get() = bool("is_user")
    public val isSystem: Boolean get() = bool("is_system")

    /** 正文。原版显示的是 `mes`；只有它为空时才回落到当前 swipe。 */
    public val text: String
        get() = string("mes").ifEmpty { swipes.getOrElse(swipeId) { "" } }

    public val sendDate: String? get() = string("send_date").takeIf { it.isNotEmpty() }

    public val swipes: List<String>
        get() = (raw["swipes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    public val swipeId: Int
        get() = (raw["swipe_id"] as? JsonPrimitive)?.let { runCatching { it.content.toInt() }.getOrNull() } ?: 0

    /** 换一条 swipe：正文与 `swipe_id` 一起改，和原版点「换一个」的行为一致。 */
    public fun withSwipe(index: Int): ChatMessage {
        require(index in swipes.indices) { "swipe 下标越界：$index，共 ${swipes.size} 条" }
        return ChatMessage(
            JsonObject(
                raw + mapOf(
                    "mes" to JsonPrimitive(swipes[index]),
                    "swipe_id" to JsonPrimitive(index),
                ),
            ),
        )
    }

    public companion object {
        /**
         * 造一条用户消息。字段顺序照原版写出的顺序，读起来和原版文件长得一样。
         * [sendDate] 必须显式传：`core` 不取系统时钟，测试才能确定。
         */
        public fun user(name: String, text: String, sendDate: String): ChatMessage = ChatMessage(
            JsonObject(
                linkedMapOf(
                    "name" to JsonPrimitive(name),
                    "is_user" to JsonPrimitive(true),
                    "is_system" to JsonPrimitive(false),
                    "send_date" to JsonPrimitive(sendDate),
                    "mes" to JsonPrimitive(text),
                    "extra" to JsonObject(
                        linkedMapOf(
                            "isSmallSys" to JsonPrimitive(false),
                            "reasoning" to JsonPrimitive(""),
                        ),
                    ),
                ),
            ),
        )

        /**
         * 造一条**带多条备选**的角色消息（导出用）。`swipes` 里包含当前显示的那一条 ——
         * 与原版落盘一致；`swipe_id` 指向它。
         */
        public fun assistantWithSwipes(
            name: String,
            swipes: List<String>,
            swipeId: Int,
            sendDate: String,
        ): ChatMessage {
            val all = swipes.ifEmpty { listOf("") }
            val index = swipeId.coerceIn(0, all.lastIndex)
            return ChatMessage(
                JsonObject(
                    linkedMapOf(
                        "name" to JsonPrimitive(name),
                        "is_user" to JsonPrimitive(false),
                        "is_system" to JsonPrimitive(false),
                        "send_date" to JsonPrimitive(sendDate),
                        "mes" to JsonPrimitive(all[index]),
                        "swipes" to JsonArray(all.map { JsonPrimitive(it) }),
                        "swipe_id" to JsonPrimitive(index),
                        "swipe_info" to JsonArray(
                            all.map { JsonObject(linkedMapOf("send_date" to JsonPrimitive(sendDate))) },
                        ),
                    ),
                ),
            )
        }

        /**
         * 造一条角色消息。带 `swipes` / `swipe_id` / `swipe_info`，这样原版打开导出的
         * 聊天文件也能正常「换一个」。
         */
        public fun assistant(name: String, text: String, sendDate: String): ChatMessage = ChatMessage(
            JsonObject(
                linkedMapOf(
                    "name" to JsonPrimitive(name),
                    "is_user" to JsonPrimitive(false),
                    "is_system" to JsonPrimitive(false),
                    "send_date" to JsonPrimitive(sendDate),
                    "mes" to JsonPrimitive(text),
                    "swipes" to JsonArray(listOf(JsonPrimitive(text))),
                    "swipe_id" to JsonPrimitive(0),
                    "swipe_info" to JsonArray(
                        listOf(
                            JsonObject(linkedMapOf("send_date" to JsonPrimitive(sendDate))),
                        ),
                    ),
                ),
            ),
        )
    }
}
