package app.tavern.core.preset

/**
 * 采样参数：只放**我们的请求真能发出去**的那几个字段。
 *
 * 原版预设里有 `top_k` / `min_p` / `top_a` / `repetition_penalty` / `seed` / `n` 等一大串，
 * 但 `:core:ai` 的 `ChatRequest` 直连 provider 时只发 OpenAI 通用的这几个
 * （见 `core/ai/OpenAiCompatibleClient.kt` 的 `buildBody`）。**收进来却不发出去等于撒谎**，
 * 所以这里只留真能生效的，其余的在解析时忽略。
 */
public data class SamplingPreset(
    public val temperature: Double? = null,
    public val topP: Double? = null,
    public val maxTokens: Int? = null,
    public val frequencyPenalty: Double? = null,
    public val presencePenalty: Double? = null,
)

/**
 * `prompt_order` 里的一条。
 *
 * 顺序＝发送顺序；[enabled] = false 的那条**不发**（原版里它是"保留在列表里但关掉"，
 * 位置也保留，重新打开就回到原位）。
 */
public data class PromptOrderEntry(
    public val identifier: String,
    public val enabled: Boolean = true,
)

/**
 * 一份 ST 预设里与我们兼容相关的部分。
 *
 * 不做的：`chat_completion_source` / 各家模型名 / `reverse_proxy` / 各种 `*_format` 之外的花活 ——
 * 那些是"原版作为代理"才需要的字段，我们直连 provider 用不上。
 */
public data class Preset(
    /** 预设名（原版就是文件名，不含 `.json`）。 */
    public val name: String,
    public val sampling: SamplingPreset,
    /**
     * 全局顺序（原版 `character_id = 100000` 那一条）。
     *
     * 空列表 = 这个预设没带 `prompt_order`，调用方应该回落到 [PresetDefaults.ORDER]。
     */
    public val order: List<PromptOrderEntry>,
    /**
     * 带 `forbid_overrides: true` 的提示词 identifier：这些段的正文**不许**被角色卡里的
     * `system_prompt` / `post_history_instructions` 顶掉（原版 `openai.js:1498/1508`）。
     * 空集 = 都可以被顶掉（原版默认）。
     */
    public val forbidOverrideIds: Set<String> = emptySet(),
    /**
     * 预设里**自带内容**的提示词，identifier → content。
     *
     * 只有 `main` / `nsfw` / `jailbreak` / `enhanceDefinitions` 这类"纯文本提示词"有内容；
     * 其余 identifier（`chatHistory` / `charDescription` …）是**注入点**，原版里 `content` 是 null。
     */
    public val prompts: Map<String, String>,
)
