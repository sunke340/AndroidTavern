package app.tavern.core.ai

import app.tavern.core.model.ChatMessage

/**
 * 一次补全请求。
 *
 * 刻意只放 OpenAI Chat Completions 的通用字段：各家的私有参数（`top_k`、`min_p`、`chat_completion_source` …）
 * 走 [extraBody]，别污染这个类型。原版那些 ST 特有的字段是给"ST server"用的，直连 provider 时不存在。
 */
public data class ChatRequest(
    public val model: String,
    public val messages: List<ChatMessage>,
    public val temperature: Double? = null,
    public val topP: Double? = null,
    public val maxTokens: Int? = null,
    public val frequencyPenalty: Double? = null,
    public val presencePenalty: Double? = null,
    public val stop: List<String> = emptyList(),
    public val stream: Boolean = true,
    /** 原样合并进请求体的额外字段（provider 私有参数）。 */
    public val extraBody: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
)
