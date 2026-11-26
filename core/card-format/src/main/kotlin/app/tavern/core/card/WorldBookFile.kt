package app.tavern.core.card

import app.tavern.core.lorebook.LorebookEntry
import app.tavern.core.lorebook.LorebookPosition
import app.tavern.core.lorebook.SelectiveLogic
import app.tavern.core.lorebook.WorldBookDecorators
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 世界书文件（ST 的 `worlds/<名字>.json`）。格式是 `{entries: {"<uid>": {…}}, originalData: …}`，
 * 字段是**已经转换过的**驼峰形态（`key` / `keysecondary` / `order` / `disable` …），
 * 与卡内嵌书那套 V2 字段名（`keys` / `secondary_keys` / `enabled`）不同，别混。
 *
 * 默认值与原版 `newWorldInfoEntryTemplate` 一致（SillyTavern 1.19.1，AGPL-3.0）：`order=100`、`probability=100`、
 * `useProbability=true`、`depth=4`、`groupWeight=100`、`selectiveLogic=0`。
 */
public class WorldBookFile(
    public val name: String,
    public val entries: List<LorebookEntry>,
)

public fun parseWorldBook(name: String, json: String): WorldBookFile {
    val root = runCatching { LENIENT.parseToJsonElement(json).jsonObject }.getOrNull()
        ?: return WorldBookFile(name, emptyList())
    val entries = root["entries"] as? JsonObject ?: return WorldBookFile(name, emptyList())

    val parsed = entries.entries.mapIndexedNotNull { rowIndex, (key, value) ->
        val obj = value as? JsonObject ?: return@mapIndexedNotNull null
        val uid = obj.int("uid") ?: key.toIntOrNull() ?: rowIndex
        // 正文开头的 @@ 装饰器：原版在加载时就剥掉并单独记下来（world-info.js:4629）
        val (decorators, content) = WorldBookDecorators.parse(obj.string("content"))

        LorebookEntry(
            world = name,
            uid = uid,
            keys = obj.stringList("key"),
            secondaryKeys = obj.stringList("keysecondary"),
            content = content,
            constant = obj.bool("constant") ?: false,
            selective = obj.bool("selective") ?: false,
            order = obj.int("order") ?: 100,
            position = LorebookPosition.fromValue(obj.int("position")),
            disabled = obj.bool("disable") ?: false,
            probability = obj.int("probability") ?: 100,
            useProbability = obj.bool("useProbability") ?: true,
            depth = obj.int("depth") ?: 4,
            selectiveLogic = SelectiveLogic.fromValue(obj.int("selectiveLogic")),
            group = obj.string("group"),
            groupOverride = obj.bool("groupOverride") ?: false,
            groupWeight = obj.int("groupWeight") ?: 100,
            scanDepth = obj.int("scanDepth"),
            caseSensitive = obj.bool("caseSensitive"),
            matchWholeWords = obj.bool("matchWholeWords"),
            useGroupScoring = obj.bool("useGroupScoring"),
            excludeRecursion = obj.bool("excludeRecursion") ?: false,
            preventRecursion = obj.bool("preventRecursion") ?: false,
            delayUntilRecursion = obj.intOrBoolAsInt("delayUntilRecursion"),
            sticky = obj.int("sticky"),
            cooldown = obj.int("cooldown"),
            delay = obj.int("delay"),
            triggers = obj.stringList("triggers"),
            ignoreBudget = obj.bool("ignoreBudget") ?: false,
            role = obj.int("role") ?: 0,
            matchPersonaDescription = obj.bool("matchPersonaDescription") ?: false,
            matchCharacterDescription = obj.bool("matchCharacterDescription") ?: false,
            matchCharacterPersonality = obj.bool("matchCharacterPersonality") ?: false,
            matchCharacterDepthPrompt = obj.bool("matchCharacterDepthPrompt") ?: false,
            matchScenario = obj.bool("matchScenario") ?: false,
            matchCreatorNotes = obj.bool("matchCreatorNotes") ?: false,
            decorators = decorators,
        )
    }

    return WorldBookFile(name, parsed)
}

private val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()

private fun JsonObject.bool(key: String): Boolean? = when ((this[key] as? JsonPrimitive)?.content) {
    "true" -> true
    "false" -> false
    else -> null
}

private fun JsonObject.stringList(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}

/** 原版这个字段可能是布尔（卡里）也可能是数字（世界书文件里），true 记成 1。 */
private fun JsonObject.intOrBoolAsInt(key: String): Int {
    val primitive = this[key] as? JsonPrimitive ?: return 0
    return when (primitive.content) {
        "true" -> 1
        "false" -> 0
        else -> primitive.content.trim().toIntOrNull() ?: 0
    }
}
