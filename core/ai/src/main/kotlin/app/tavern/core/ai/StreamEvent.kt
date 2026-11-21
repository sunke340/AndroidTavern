package app.tavern.core.ai

/** 流式生成过程中可能收到的事件。 */
public sealed interface StreamEvent {
    /** 一段增量文本（可能为空串的 chunk 会被上游吞掉，不会发出来）。 */
    public data class Delta(public val text: String) : StreamEvent

    /** 正常结束，带上 provider 给的 finish_reason（可能为 null）。 */
    public data class Completed(public val finishReason: String? = null) : StreamEvent

    /** 失败。流是"失败也走事件"，不抛异常——调用方需要把错误渲染到聊天里，而不是崩掉。 */
    public data class Failed(
        public val message: String,
        public val httpCode: Int? = null,
    ) : StreamEvent
}

/** 非流式补全的结果。 */
public data class ChatResult(
    public val text: String,
    public val finishReason: String? = null,
)
