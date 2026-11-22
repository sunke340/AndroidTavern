package app.tavern.core.model

/**
 * 一套模型连接档案（"我用哪家 provider、哪个模型"）。
 *
 * 为什么要多条：直连 provider 时 baseUrl / 模型 id / Key / 自定义头是**绑在一起**的一组值，
 * 换 provider 只改其中一个就会 401 或 404。把它们收成一个可命名、可切换的档案，
 * 用户才能在「DeepSeek 官网」和「本地 Ollama」之间来回切，而不是每次手抄四个输入框。
 *
 * 字段刻意只放"发请求真正需要的东西"：私有参数（`top_k`、`chat_completion_source` …）
 * 走 [headers] 或请求的 `extraBody`，别长在这个类型上。
 */
public data class ModelProfile(
    public val id: String,
    public val name: String,
    public val baseUrl: String,
    public val model: String,
    /**
     * 思考强度（OpenAI 兼容的 `reasoning_effort`：`minimal` / `low` / `medium` / `high`）。
     * null = 不往请求里放这个字段（大多数 provider 默认就用不着）。
     */
    public val reasoningEffort: String? = null,
    /** 额外请求头（中转站常用的 `X-Title`、`HTTP-Referer` 之类）。 */
    public val headers: Map<String, String> = emptyMap(),
    public val isDefault: Boolean = false,
)

/** 档案相关的纯逻辑：入库格式、Key 的命名、"有且只有一个默认"。 */
public object ModelProfiles {

    /** 老版本（只有单套连接配置）迁移出来的那一条档案的 id。 */
    public const val LEGACY_ID: String = "default"

    /** 首次安装、也什么都没有时用的兜底档案。 */
    public const val FALLBACK_NAME: String = "默认模型"
    public const val FALLBACK_BASE_URL: String = "https://api.deepseek.com/v1"
    public const val FALLBACK_MODEL: String = "deepseek-chat"

    /** 每个档案的 Key 在 data 层 SecretStore（Keystore 加密）里的名字。 */
    public fun keyName(profileId: String): String = "api_key:$profileId"

    /**
     * 保证"有且只有一个默认"。
     *
     * 数据库里可能出现 0 个默认（删掉了默认档案）或 2 个（历史脏数据），
     * 这两种情况在 UI 上都会表现为"点发送时不知道该用哪个"，所以在读取时就地和一次。
     */
    public fun normalizeDefault(list: List<ModelProfile>): List<ModelProfile> {
        if (list.isEmpty()) return list
        val defaultIndex = list.indexOfFirst { it.isDefault }
        val keep = if (defaultIndex >= 0) defaultIndex else 0
        return list.mapIndexed { index, profile ->
            if (profile.isDefault == (index == keep)) profile else profile.copy(isDefault = index == keep)
        }
    }

    /** 当前生效的那条：默认档案；没有默认就取第一条；空列表返回 null。 */
    public fun active(list: List<ModelProfile>): ModelProfile? =
        normalizeDefault(list).firstOrNull { it.isDefault }

    /**
     * 请求头 ↔ 一行一条的文本（`Name: value`）。
     *
     * 为什么不用 JSON：这是给人手填的字段，一行一条既能被直接看懂，也不怕手抖打错引号。
     * 值里允许出现冒号（URL、时间戳），所以只按**第一个**冒号切。
     */
    public fun formatHeaders(headers: Map<String, String>): String =
        headers.entries
            .filter { it.key.isNotBlank() }
            .joinToString("\n") { "${it.key.trim()}: ${it.value.trim()}" }

    public fun parseHeaders(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) result[name] = value
        }
        return result
    }
}
