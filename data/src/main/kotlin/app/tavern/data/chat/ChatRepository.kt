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
    /**
     * 最后一条消息的正文，**只给列表预览用**。
     *
     * 列表要知道"这场聊到哪了"，否则每行只能显示日期和条数。取法是 SQL 里的
     * `MAX(id)` 子查询（不整表载入，见 data/AGENTS.md 的分页规矩）。
     * 没有消息（理论上不该有）时为 `null`。
     */
    val lastMessage: String? = null,
    /** 最后一条是不是用户发的：预览前要加「你：」。 */
    val lastFromUser: Boolean = false,
)

/**
 * 导入时的一条消息：日期**原样保留**（导入的历史不该变成"今天"），
 * `swipes` 只有最后一条助手消息可能有。
 */
data class ImportMessage(
    val isUser: Boolean,
    val content: String,
    val sendDate: String,
    val isSystem: Boolean = false,
    val swipes: List<String> = emptyList(),
    val swipeId: Int = 0,
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

    /**
     * 改写一条消息的正文（换开场白用：开场白就是会话里的第一条消息）。
     *
     * 只改这一条，不动 id 与 send_date —— 顺序与时间线不能因为换了个开场白就乱掉。
     */
    fun updateMessage(id: Long, content: String)

    /**
     * 一条消息的全部 swipe 备选（按顺序）。
     *
     * 对齐原版 jsonl 的 `swipes[]`：**当前显示的那一条也在里面**，`swipe_id` 只是下标。
     * 只有最后一条助手消息会有多个。
     */
    fun swipes(messageId: Long): List<String>

    /** 覆盖一条消息的 swipe 备选（不动 `messages.content` 与时间）。 */
    fun setSwipes(messageId: Long, swipes: List<String>)

    /** 删掉某张卡的全部会话（连带消息）。返回删掉的会话数。 */
    fun deleteSessionsForCard(cardId: String): Int

    /**
     * 导入一段聊天：建一个会话 + 批量写消息（单事务，一次失败不会留下半个会话）。
     *
     * `title` 由调用方给（通常是"导入 卡名 时间"）；返回新会话 id。
     */
    fun importSession(
        cardId: String,
        cardName: String,
        title: String,
        messages: List<ImportMessage>,
    ): String
}
