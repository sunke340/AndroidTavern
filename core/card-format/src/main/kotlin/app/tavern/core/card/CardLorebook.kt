package app.tavern.core.card

import app.tavern.core.lorebook.LorebookEntry
import app.tavern.core.lorebook.WorldBookDecorators
import app.tavern.core.lorebook.LorebookPosition
import app.tavern.core.lorebook.SelectiveLogic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把卡内嵌世界书（V2/V3 的 `character_book.entries`）映射成扫描器认识的条目。
 *
 * 映射规则逐字段对齐原版 `convertCharacterBook()`（SillyTavern 1.19.1，AGPL-3.0，
 * `public/scripts/world-info.js`），包括几个反直觉的地方：
 *
 * - `disable` 是 `!entry.enabled`，所以卡里**没写** `enabled` 的条目在原版里是**关**的；
 * - `position` 优先取 `extensions.position`（数字），没有才看 `position: "before_char"`；
 * - `delayUntilRecursion` 卡里是布尔，原版记成 `true`，比较时按 1 处理；
 * - `depth` / `probability` / `groupWeight` 等缺省值与原版常量一致。
 */
public fun CharacterCard.lorebookEntries(): List<LorebookEntry> {
    val book = characterBook ?: return emptyList()
    val rawEntries = book["entries"] as? JsonArray ?: return emptyList()
    val world = name.ifBlank { "character" }

    return rawEntries.mapIndexedNotNull { index, element ->
        val entry = element as? JsonObject ?: return@mapIndexedNotNull null
        val extensions = entry["extensions"] as? JsonObject ?: JsonObject(emptyMap())

        val position = extensions.int("position")
            ?: if (entry.string("position") == "before_char") LorebookPosition.BeforeChar.value else LorebookPosition.AfterChar.value

        val delayUntilRecursion = when (val value = extensions["delay_until_recursion"]) {
            null -> 0
            is JsonPrimitive -> if (value.content == "true") 1 else value.intOrNull() ?: 0
            else -> 0
        }

        // 卡内嵌书的正文同样可能有 @@ 装饰器（原版加载时统一 parseDecorators）
        val (decorators, content) = WorldBookDecorators.parse(entry.string("content"))

        LorebookEntry(
            world = world,
            uid = entry.int("id") ?: index,
            keys = entry.stringList("keys"),
            secondaryKeys = entry.stringList("secondary_keys"),
            content = content,
            constant = entry.bool("constant") ?: false,
            selective = entry.bool("selective") ?: false,
            order = entry.int("insertion_order") ?: 100,
            position = LorebookPosition.fromValue(position),
            // 原版卡里 outlet 名在 `extensions.outlet_name`（V2 映射时从 `outletName` 来）
            outletName = extensions.string("outlet_name").orEmpty(),
            // 原版就是 !entry.enabled：缺字段等于关闭
            disabled = entry.bool("enabled") != true,
            probability = extensions.int("probability") ?: 100,
            useProbability = extensions.bool("useProbability") ?: true,
            depth = extensions.int("depth") ?: DEFAULT_DEPTH,
            selectiveLogic = SelectiveLogic.fromValue(extensions.int("selectiveLogic")),
            group = extensions.string("group"),
            groupOverride = extensions.bool("group_override") ?: false,
            groupWeight = extensions.int("group_weight") ?: DEFAULT_WEIGHT,
            scanDepth = extensions.int("scan_depth"),
            caseSensitive = extensions.bool("case_sensitive"),
            matchWholeWords = extensions.bool("match_whole_words"),
            useGroupScoring = extensions.bool("use_group_scoring"),
            excludeRecursion = extensions.bool("exclude_recursion") ?: false,
            preventRecursion = extensions.bool("prevent_recursion") ?: false,
            delayUntilRecursion = delayUntilRecursion,
            sticky = extensions.int("sticky"),
            cooldown = extensions.int("cooldown"),
            delay = extensions.int("delay"),
            triggers = extensions.stringList("triggers"),
            ignoreBudget = extensions.bool("ignore_budget") ?: false,
            role = extensions.int("role") ?: 0,
            matchPersonaDescription = extensions.bool("match_persona_description") ?: false,
            matchCharacterDescription = extensions.bool("match_character_description") ?: false,
            matchCharacterPersonality = extensions.bool("match_character_personality") ?: false,
            matchCharacterDepthPrompt = extensions.bool("match_character_depth_prompt") ?: false,
            matchScenario = extensions.bool("match_scenario") ?: false,
            matchCreatorNotes = extensions.bool("match_creator_notes") ?: false,
            decorators = decorators,
        )
    }
}

private const val DEFAULT_DEPTH = 4
private const val DEFAULT_WEIGHT = 100

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull()

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let { primitive ->
        when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

private fun JsonObject.stringList(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}

private fun JsonPrimitive.intOrNull(): Int? = content.trim().toIntOrNull()
