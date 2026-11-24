package app.tavern.core.card

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean

/**
 * 卡内嵌世界书的一条条目。**只是给界面列出来用**——激活算法在 `:core:lorebook`。
 *
 * 字段含义对齐 ST 的 `character_book.entries[i]`：
 * - `comment` 是给人看的标题，没有就退回 `name`；
 * - `keys` 是触发关键字（老格式写成单数 `key`，两种都认）；
 * - `content` 是条目正文（界面拿它做两行预览，和全局世界书详情页一个样子）；
 * - `enabled` 缺省为 **true**（这里只是"卡里怎么写"；真正生效与否见 [lorebookEntries]：
 *   原版 `convertCharacterBook()` 把 `!entry.enabled` 记成 `disable`，**没写就等于关**）。
 */
public class CardBookEntry(
    public val index: Int,
    public val comment: String,
    public val keys: List<String>,
    public val content: String,
    public val enabled: Boolean,
)

/** 卡内嵌世界书的条目列表；没有内嵌书就是空列表。 */
public fun CharacterCard.bookEntries(): List<CardBookEntry> {
    val entries = characterBook?.get("entries") as? JsonArray ?: return emptyList()

    return entries.mapIndexedNotNull { index, element ->
        val entry = element as? JsonObject ?: return@mapIndexedNotNull null
        CardBookEntry(
            index = index,
            comment = entry.stringField("comment")
                .ifBlank { entry.stringField("name") }
                .ifBlank { "条目 ${index + 1}" },
            keys = entry.keysField(),
            content = entry.stringField("content"),
            enabled = entry.booleanField("enabled") ?: true,
        )
    }
}

private fun JsonObject.stringField(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.booleanField(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() }

private fun JsonObject.keysField(): List<String> {
    for (name in listOf("keys", "key")) {
        val array = this[name] as? JsonArray ?: continue
        return array.mapNotNull { (it as? JsonPrimitive)?.content }.filter { it.isNotBlank() }
    }
    return emptyList()
}
