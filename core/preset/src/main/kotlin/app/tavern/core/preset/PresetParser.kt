package app.tavern.core.preset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 预设文件解析（SillyTavern 1.19.1 的 `.json`）。
 *
 * 只认我们真会用到的字段，**不认识的键一律忽略**——原版预设里有几十个 provider 专有字段，
 * 我们直连 API 用不上，也不该因为它们存在就解析失败。
 */
public object PresetParser {
    /**
     * 预设里"全局顺序"那一条的伪角色 id。
     *
     * 出处：`public/scripts/PromptManager.js` 的 `configuration.promptOrder.dummyId = 100000`
     * —— 那是文本补全那条线；**聊天补全**（OpenAI 兼容接口）用的是
     * `public/scripts/openai.js` 里的 `dummyId: 100001`。
     *
     * 原版 Default.json 于是有**两张表**：100000 那张没有 `personaDescription`，
     * 100001 那张有（在 `worldInfoBefore` 之后、`charDescription` 之前）。
     * 本项目只走聊天补全，所以优先 100001，没有才退回 100000、再退回第一条。
     */
    public const val CHAT_ORDER_ID: String = "100001"

    /** 文本补全那条线的全局顺序（老预设只有这一张）。 */
    public const val GLOBAL_ORDER_ID: String = "100000"

    /**
     * @param name 预设名（原版存的就是文件名去掉 `.json`）
     * @throws IllegalArgumentException JSON 不合法时
     */
    public fun parse(name: String, json: String): Preset {
        val root = runCatching { Json.parseToJsonElement(json) }
            .getOrNull() as? JsonObject
            ?: throw IllegalArgumentException("不是合法的预设 JSON：$name")

        return Preset(
            name = name,
            sampling = SamplingPreset(
                temperature = root.double("temperature"),
                topP = root.double("top_p"),
                maxTokens = root.int("openai_max_tokens"),
                frequencyPenalty = root.double("frequency_penalty"),
                presencePenalty = root.double("presence_penalty"),
            ),
            order = parseOrder(root["prompt_order"]),
            prompts = parsePrompts(root["prompts"]),
            forbidOverrideIds = parseForbidOverrides(root["prompts"]),
        )
    }

    private fun parseOrder(element: JsonElement?): List<PromptOrderEntry> {
        val lists = (element as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyList()
        // 优先聊天补全那张表（100001）；再退文本补全那张（100000）；
        // 老预设可能没有 character_id，最后退化成第一条。
        val chosen = lists.firstOrNull { it.primitive("character_id") == CHAT_ORDER_ID }
            ?: lists.firstOrNull { it.primitive("character_id") == GLOBAL_ORDER_ID }
            ?: lists.firstOrNull()
            ?: return emptyList()
        val entries = chosen["order"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val identifier = entry.primitive("identifier") ?: return@mapNotNull null
            PromptOrderEntry(
                identifier = identifier,
                enabled = entry.primitive("enabled") != "false",
            )
        }
    }

    private fun parsePrompts(element: JsonElement?): Map<String, String> {
        val list = (element as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (prompt in list) {
            val identifier = prompt.primitive("identifier") ?: continue
            // 注入点（chatHistory / charDescription …）的 content 是 null，不收
            val content = prompt.primitive("content") ?: continue
            result[identifier] = content
        }
        return result
    }

    /**
     * 哪些提示词**禁止被角色卡顶掉**（原版每条 `Prompt` 上的 `forbid_overrides`）。
     *
     * 只有 `true` 才算；没写 / `false` 都是"可以被顶掉"，与原版的 `!== true` 判定一致。
     */
    private fun parseForbidOverrides(element: JsonElement?): Set<String> {
        val list = (element as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptySet()
        return list.filter { it.primitive("forbid_overrides") == "true" }
            .mapNotNull { it.primitive("identifier") }
            .toSet()
    }

    /** 取出标量的原始文本：数字与字符串都按文本比（`character_id` 两种写法都见过）。 */
    private fun JsonObject.primitive(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
}
