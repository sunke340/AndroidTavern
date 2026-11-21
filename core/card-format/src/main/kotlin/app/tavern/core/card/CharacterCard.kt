package app.tavern.core.card

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 角色卡。原始 JSON 一直是真相，取值只是视图。
 *
 * 为什么保留 `raw` 而不是解析成强类型再丢掉：卡片里有大量我们不理解的字段
 * （`character_book`、`extensions`、V3 的 `assets`、各家 App 塞的私有键），
 * 一旦解析成强类型再写回，用户那些字段就没了。**读进来什么样，写出去还得什么样。**
 *
 * 支持 V2（`{spec, data:{...}}`）与 V1（字段直接躺在顶层，无 `spec`）。
 */
public class CharacterCard internal constructor(
    public val raw: JsonObject,
) {
    private val data: JsonObject
        get() = raw["data"]?.jsonObject ?: raw

    private fun text(key: String): String =
        (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun array(key: String): JsonArray? = data[key] as? JsonArray

    public val spec: String? get() = (raw["spec"] as? JsonPrimitive)?.content
    public val specVersion: String? get() = (raw["spec_version"] as? JsonPrimitive)?.content

    public val name: String get() = text("name")
    public val description: String get() = text("description")
    public val personality: String get() = text("personality")
    public val scenario: String get() = text("scenario")
    public val firstMessage: String get() = text("first_mes")
    public val messageExample: String get() = text("mes_example")
    public val creatorNotes: String get() = text("creator_notes")
    public val systemPrompt: String get() = text("system_prompt")
    public val postHistoryInstructions: String get() = text("post_history_instructions")
    public val creator: String get() = text("creator")
    public val characterVersion: String get() = text("character_version")

    public val alternateGreetings: List<String>
        get() = array("alternate_greetings")?.mapNotNull { it.stringOrNull() }.orEmpty()

    public val tags: List<String>
        get() = array("tags")?.mapNotNull { it.stringOrNull() }.orEmpty()

    /** 卡内嵌世界书（V2 `character_book`）。条目结构见世界书模块，这里只交出去。 */
    public val characterBook: JsonObject?
        get() = data["character_book"] as? JsonObject

    /** 换掉某个顶层 data 字段后得到一张新卡（写回 PNG 时用）。 */
    public fun withField(key: String, value: JsonElement): CharacterCard {
        val target = if (raw["data"] is JsonObject) raw.getValue("data").jsonObject else raw
        val newTarget = JsonObject(target + (key to value))
        return if (raw["data"] is JsonObject) {
            CharacterCard(JsonObject(raw + ("data" to newTarget)))
        } else {
            CharacterCard(newTarget)
        }
    }

    public companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        public fun fromJson(text: String): CharacterCard = CharacterCard(json.parseToJsonElement(text).jsonObject)
    }
}

private fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
