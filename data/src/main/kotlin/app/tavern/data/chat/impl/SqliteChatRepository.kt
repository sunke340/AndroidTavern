package app.tavern.data.chat.impl

import android.content.ContentValues
import android.content.Context
import app.tavern.data.chat.ChatRepository
import app.tavern.data.chat.ChatSession
import app.tavern.data.chat.StoredMessage
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * 原生 SQLite 实现（ADR 0003）。
 *
 * 时间戳：`created_at` / `updated_at` 存 epoch 毫秒（排序、比较方便），
 * 消息的 `send_date` 存**原版格式的 ISO 字符串**（导出 `.jsonl` 时能原样写出去）。
 */
class SqliteChatRepository(context: Context) : ChatRepository {

    // 与 alias 共用同一个 helper：两个实例会有两条连接抢锁
    private val helper = TavernDatabases.of(context)

    override fun sessions(): List<ChatSession> {
        val sql = """
            SELECT s.id, s.card_id, s.card_name, s.title, s.created_at, s.updated_at,
                   COUNT(m.id) AS message_count,
                   lm.content AS last_content,
                   lm.is_user AS last_is_user
            FROM ${TavernDatabase.TABLE_SESSIONS} s
            LEFT JOIN ${TavernDatabase.TABLE_MESSAGES} m ON m.session_id = s.id
            LEFT JOIN ${TavernDatabase.TABLE_MESSAGES} lm
                   ON lm.id = (
                       SELECT MAX(id) FROM ${TavernDatabase.TABLE_MESSAGES} WHERE session_id = s.id
                   )
            GROUP BY s.id
            ORDER BY s.updated_at DESC, s.created_at DESC
        """.trimIndent()

        return helper.readableDatabase.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ChatSession(
                            id = cursor.getString(0),
                            cardId = cursor.getString(1),
                            cardName = cursor.getString(2),
                            title = cursor.getString(3),
                            createdAt = cursor.getLong(4),
                            updatedAt = cursor.getLong(5),
                            messageCount = cursor.getInt(6),
                            // 一条消息都没有时两列都是 NULL（LEFT JOIN）
                            lastMessage = if (cursor.isNull(7)) null else cursor.getString(7),
                            lastFromUser = !cursor.isNull(8) && cursor.getInt(8) != 0,
                        ),
                    )
                }
            }
        }
    }

    override fun createSession(cardId: String, cardName: String, title: String): ChatSession {
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            cardId = cardId,
            cardName = cardName,
            title = title,
            createdAt = now,
            updatedAt = now,
            messageCount = 0,
        )

        val values = ContentValues().apply {
            put("id", session.id)
            put("card_id", session.cardId)
            put("card_name", session.cardName)
            put("title", session.title)
            put("created_at", session.createdAt)
            put("updated_at", session.updatedAt)
        }
        helper.writableDatabase.insertOrThrow(TavernDatabase.TABLE_SESSIONS, null, values)
        return session
    }

    override fun deleteSession(id: String) {
        helper.writableDatabase.delete(TavernDatabase.TABLE_SESSIONS, "id = ?", arrayOf(id))
    }

    override fun messages(sessionId: String): List<StoredMessage> {
        val sql = """
            SELECT id, is_user, is_system, content, send_date
            FROM ${TavernDatabase.TABLE_MESSAGES}
            WHERE session_id = ?
            ORDER BY id ASC
        """.trimIndent()

        return helper.readableDatabase.rawQuery(sql, arrayOf(sessionId)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StoredMessage(
                            id = cursor.getLong(0),
                            isUser = cursor.getInt(1) != 0,
                            isSystem = cursor.getInt(2) != 0,
                            content = cursor.getString(3),
                            sendDate = cursor.getString(4),
                        ),
                    )
                }
            }
        }
    }

    override fun deleteSessionsForCard(cardId: String): Int =
        helper.writableDatabase.delete(
            TavernDatabase.TABLE_SESSIONS,
            "card_id = ?",
            arrayOf(cardId),
        )

    override fun deleteMessage(id: Long) {
        helper.writableDatabase.delete(TavernDatabase.TABLE_MESSAGES, "id = ?", arrayOf(id.toString()))
    }

    override fun updateMessage(id: Long, content: String) {
        val values = ContentValues().apply { put("content", content) }
        helper.writableDatabase.update(
            TavernDatabase.TABLE_MESSAGES,
            values,
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    override fun append(sessionId: String, isUser: Boolean, content: String): StoredMessage {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        val sendDate = Iso8601.format(now)

        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("session_id", sessionId)
                put("is_user", if (isUser) 1 else 0)
                put("is_system", 0)
                put("content", content)
                put("send_date", sendDate)
            }
            val id = db.insertOrThrow(TavernDatabase.TABLE_MESSAGES, null, values)

            val touch = ContentValues().apply { put("updated_at", now) }
            db.update(TavernDatabase.TABLE_SESSIONS, touch, "id = ?", arrayOf(sessionId))

            db.setTransactionSuccessful()
            return StoredMessage(
                id = id,
                isUser = isUser,
                isSystem = false,
                content = content,
                sendDate = sendDate,
            )
        } finally {
            db.endTransaction()
        }
    }
}

/** 原版 `send_date` 的形状：`2026-11-21T17:14:24.829Z`（UTC、三位毫秒、Z 结尾）。 */
internal object Iso8601 {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .withZone(ZoneOffset.UTC)

    fun format(epochMillis: Long): String = formatter.format(Instant.ofEpochMilli(epochMillis))
}
