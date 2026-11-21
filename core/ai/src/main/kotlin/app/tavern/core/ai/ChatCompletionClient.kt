package app.tavern.core.ai

import kotlinx.coroutines.flow.Flow

/**
 * 聊天补全客户端。
 *
 * 接口只有两个动作，因为这两件事的语义差别很大：流式要能"边收边显示 + 随时取消"，
 * 非流式是一次性拿全。把它们塞进一个 `Boolean` 会让两边的错误处理和取消路径都变扭。
 */
public interface ChatCompletionClient {
    /** 流式生成。取消 flow 就会中断底层 HTTP 请求。 */
    public fun stream(request: ChatRequest): Flow<StreamEvent>

    /** 一次性生成，失败抛 [ChatCompletionException]。 */
    public suspend fun complete(request: ChatRequest): ChatResult
}

public class ChatCompletionException(
    message: String,
    public val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
