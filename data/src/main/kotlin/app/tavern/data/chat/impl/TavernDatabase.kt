package app.tavern.data.chat.impl

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 表结构：会话、消息、模型档案。SQL 全部集中在这里，改表必须同时写 [onUpgrade] 的迁移
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
        db.execSQL(CREATE_SESSIONS)
        db.execSQL(CREATE_MESSAGES)
        db.execSQL(CREATE_MESSAGES_INDEX)
        db.execSQL(CREATE_MESSAGE_SWIPES)
        db.execSQL(CREATE_PROFILES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        // v1 → v2：只**增加**「模型档案」表。会话与消息一行都不动 —— 升级不能丢聊天记录。
        if (version < 2) {
            db.execSQL(CREATE_PROFILES)
            version = 2
        }
        // v2 → v3：只**增加**「消息 swipe 备选」表（P0-6）。原来的消息一行都不动。
        // 为什么单开一张表而不是给 messages 加一列 JSON：备选是"一对多"，存 JSON 要自己
        // 处理转义；原版 jsonl 里是 swipes[]，我们在库里用关系表表达同一件事。
        if (version < 3) {
            db.execSQL(CREATE_MESSAGE_SWIPES)
            version = 3
        }
        // 漏写迁移就炸出来，绝不用 DROP 了事
        if (version != newVersion) error("没有 $oldVersion → $newVersion 的迁移")
    }

    companion object {
        const val DATABASE_NAME = "tavern.db"
        const val VERSION = 3
        const val TABLE_SESSIONS = "sessions"
        const val TABLE_MESSAGES = "messages"
        const val TABLE_PROFILES = "model_profiles"
        const val TABLE_MESSAGE_SWIPES = "message_swipes"

        private const val CREATE_SESSIONS = "CREATE TABLE IF NOT EXISTS $TABLE_SESSIONS (" +
            "id TEXT PRIMARY KEY NOT NULL," +
            "card_id TEXT NOT NULL," +
            "card_name TEXT NOT NULL," +
            "title TEXT NOT NULL," +
            "created_at INTEGER NOT NULL," +
            "updated_at INTEGER NOT NULL)"

        private const val CREATE_MESSAGES = "CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "session_id TEXT NOT NULL," +
            "is_user INTEGER NOT NULL," +
            "is_system INTEGER NOT NULL DEFAULT 0," +
            "content TEXT NOT NULL," +
            "send_date TEXT NOT NULL," +
            "FOREIGN KEY (session_id) REFERENCES $TABLE_SESSIONS(id) ON DELETE CASCADE)"

        private const val CREATE_MESSAGES_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_messages_session ON $TABLE_MESSAGES(session_id, id)"

        private const val CREATE_MESSAGE_SWIPES = "CREATE TABLE IF NOT EXISTS $TABLE_MESSAGE_SWIPES (" +
            "message_id INTEGER NOT NULL," +
            "seq INTEGER NOT NULL," +
            "content TEXT NOT NULL," +
            "PRIMARY KEY (message_id, seq)," +
            "FOREIGN KEY (message_id) REFERENCES $TABLE_MESSAGES(id) ON DELETE CASCADE)"

        // headers 存"一行一条 Name: value"的原始文本（UI 里就是这个形状，存取不用转义）
        private const val CREATE_PROFILES = "CREATE TABLE IF NOT EXISTS $TABLE_PROFILES (" +
            "id TEXT PRIMARY KEY NOT NULL," +
            "name TEXT NOT NULL," +
            "base_url TEXT NOT NULL," +
            "model TEXT NOT NULL," +
            "reasoning_effort TEXT," +
            "headers TEXT NOT NULL DEFAULT ''," +
            "is_default INTEGER NOT NULL DEFAULT 0)"
    }
}

/**
 * 同一个数据库只开一个 [SQLiteOpenHelper]。
 *
 * 两个仓储各 `new` 一个的话会有两条连接抢一把锁，非 WAL 模式下容易出现 "database is locked"。
 */
internal object TavernDatabases {

    @Volatile
    private var instance: TavernDatabase? = null

    fun of(context: Context): TavernDatabase = instance ?: synchronized(this) {
        instance ?: TavernDatabase(context).also { instance = it }
    }
}
