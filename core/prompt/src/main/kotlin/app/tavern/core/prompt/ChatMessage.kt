package app.tavern.core.prompt

/** 一条待发送的消息。刻意只有两个字段：原版在这一层也没有第三种东西。 */
public data class ChatMessage(
    public val role: ChatRole,
    public val content: String,
)
