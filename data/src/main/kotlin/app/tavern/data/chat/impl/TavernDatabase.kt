package app.tavern.data.chat.impl

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 表结构就两张：会话、消息。SQL 全部集中在这里，改表必须同时写 [onUpgrade] 的迁移
 * （为什么不用 Room，见 `docs/adr/0003-sqlite-over-room.md`）。
 *
 * 字段命名故意贴近原版 `.jsonl`：`is_user` / `is_system` / `send_date`，少一层心智转换。
 */
internal class TavernDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 删会话时连带删消息，别留孤儿行
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SESSIONS (
                id TEXT PRIMARY KEY NOT NULL,
                card_id TEXT NOT NULL,
                card_name TEXT NOT NULL,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                is_user INTEGER NOT NULL,
                is_system INTEGER NOT NULL DEFAULT 0,
                content TEXT NOT NULL,
                send_date TEXT NOT NULL,
                FOREIGN KEY (session_id) REFERENCES $TABLE_SESSIONS(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_session ON $TABLE_MESSAGES(session_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 还没有历史版本。加字段/改表时在这里写分支，逐版本迁移，绝不要 DROP 了事。
        error("没有 $oldVersion → $newVersion 的迁移")
    }

    companion object {
        const val DATABASE_NAME = "tavern.db"
        const val VERSION = 1
        const val TABLE_SESSIONS = "sessions"
        const val TABLE_MESSAGES = "messages"
    }
}
