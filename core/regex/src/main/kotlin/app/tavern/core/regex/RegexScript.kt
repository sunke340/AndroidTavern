package app.tavern.core.regex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** 脚本作用在哪段文本上。数值对齐原版 `regex_placement`（`extensions/regex/engine.js:277`）。 */
public enum class RegexPlacement(public val value: Int) {
    /** 0：已废弃的 Markdown 显示位。 */
    MarkdownDisplay(0),

    /** 1：用户输入。 */
    UserInput(1),

    /** 2：模型输出（只影响显示）。 */
    AiOutput(2),

    /** 3：斜杠命令。 */
    SlashCommand(3),

    /** 5：世界书条目。 */
    WorldInfo(5),

    /** 6：推理内容。 */
    Reasoning(6),
    ;

    public companion object {
        public fun fromValue(value: Int): RegexPlacement? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 一条正则脚本（对齐原版 `RegexScriptData`）。
 *
 * [findRegex] 是**斜杠包起来的字符串**（`/pattern/flags`），不是编译好的正则；
 * [replaceString] 支持 JS 那套 `$1` / `$<name>` / `{{match}}` 写法，见 [RegexEngine.run]。
 */
public class RegexScript(
    public val name: String = "",
    public val findRegex: String = "",
    public val replaceString: String = "",
    /** 从捕获组里删掉这些子串（先做宏替换）。 */
    public val trimStrings: List<String> = emptyList(),
    public val placement: Set<RegexPlacement> = emptySet(),
    public val disabled: Boolean = false,
    /** 只在渲染 Markdown 时跑（即显示层，不影响提示词）。 */
    public val markdownOnly: Boolean = false,
    /** 只在拼提示词时跑。 */
    public val promptOnly: Boolean = false,
    public val runOnEdit: Boolean = false,
    /** 0=原样用；1=先做宏替换；2=宏替换后把结果当字面量转义。 */
    public val substituteRegex: Int = 0,
    public val minDepth: Int? = null,
    public val maxDepth: Int? = null,
)

/**
 * 解析正则脚本。
 *
 * 认两种形态：`[ {…}, {…} ]`（原版"导出脚本"给的就是这个）和 `{ "regex": [ … ] }`
 * （ST 的 `settings.json` 里 `extension_settings.regex` 那一坨）。认不出来就是空列表——
 * 宁可不跑，也不要把用户的设置文件搞坏。
 */
public fun parseRegexScripts(json: String): List<RegexScript> {
    val root = runCatching { LENIENT.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
    val array = when (root) {
        is JsonArray -> root
        is JsonObject -> root["regex"] as? JsonArray ?: return emptyList()
        else -> return emptyList()
    }

    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        RegexScript(
            name = obj.string("scriptName"),
            findRegex = obj.string("findRegex"),
            replaceString = obj.string("replaceString"),
            trimStrings = obj.stringList("trimStrings"),
            placement = obj.intList("placement").mapNotNull { RegexPlacement.fromValue(it) }.toSet(),
            disabled = obj.bool("disabled") ?: false,
            markdownOnly = obj.bool("markdownOnly") ?: false,
            promptOnly = obj.bool("promptOnly") ?: false,
            runOnEdit = obj.bool("runOnEdit") ?: false,
            substituteRegex = obj.int("substituteRegex") ?: 0,
            minDepth = obj.int("minDepth"),
            maxDepth = obj.int("maxDepth"),
        )
    }
}

/**
 * 写回原版能读的脚本数组。字段名照搬 ST（`scriptName` / `findRegex` / `placement` …），
 * 因为导入进来的东西要能再导出去用——这是"往返可迁移"的一部分。
 */
public fun writeRegexScripts(scripts: List<RegexScript>): String {
    val array = JsonArray(
        scripts.map { script ->
            JsonObject(
                mapOf(
                    "scriptName" to JsonPrimitive(script.name),
                    "findRegex" to JsonPrimitive(script.findRegex),
                    "replaceString" to JsonPrimitive(script.replaceString),
                    "trimStrings" to JsonArray(script.trimStrings.map { JsonPrimitive(it) }),
                    "placement" to JsonArray(script.placement.sortedBy { it.value }.map { JsonPrimitive(it.value) }),
                    "disabled" to JsonPrimitive(script.disabled),
                    "markdownOnly" to JsonPrimitive(script.markdownOnly),
                    "promptOnly" to JsonPrimitive(script.promptOnly),
                    "runOnEdit" to JsonPrimitive(script.runOnEdit),
                    "substituteRegex" to JsonPrimitive(script.substituteRegex),
                    "minDepth" to (script.minDepth?.let { JsonPrimitive(it) } ?: JsonNull),
                    "maxDepth" to (script.maxDepth?.let { JsonPrimitive(it) } ?: JsonNull),
                ),
            )
        },
    )
    return array.toString()
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

private fun JsonObject.intList(key: String): List<Int> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.toIntOrNull() }
}
