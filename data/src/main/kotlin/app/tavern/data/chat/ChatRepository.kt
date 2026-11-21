package app.tavern.data.chat

/** 一个聊天会话（原版里对应"角色 + 一个 .jsonl 文件"）。 */
data class ChatSession(
    val id: String,
    val cardId: String,
    val cardName: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
)

/** 库里的一条消息。`sendDate` 是原版格式的 ISO 时间（`2026-11-21T17:14:24.829Z`）。 */
data class StoredMessage(
    val id: Long,
    val isUser: Boolean,
    val isSystem: Boolean,
    val content: String,
    val sendDate: String,
)

/**
 * 聊天记录的本地存储。**DB 是运行时唯一真相**，JSONL 只在导入/导出时生成
 * （见 `data/AGENTS.md`：不要学原版拿 JSONL 当数据库）。
 */
interface ChatRepository {
    /** 按最近活动倒序。 */
    fun sessions(): List<ChatSession>

    fun createSession(cardId: String, cardName: String, title: String): ChatSession

    fun deleteSession(id: String)

    /** 一次读完一个会话的消息。分页接口等长聊天真的出现再加（见 ADR 0003 的约束）。 */
    fun messages(sessionId: String): List<StoredMessage>

    /** 追加一条消息并更新会话的 `updated_at`。返回入库后的消息（带自增 id）。 */
    fun append(sessionId: String, isUser: Boolean, content: String): StoredMessage

    /** 撤回一条消息。请求失败时把刚写进去的用户消息撤掉，避免重试一次多一条。 */
    fun deleteMessage(id: Long)
}
