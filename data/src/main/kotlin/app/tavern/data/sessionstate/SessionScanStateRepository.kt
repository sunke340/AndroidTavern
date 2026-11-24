package app.tavern.data.sessionstate

/**
 * 会话的世界书计时状态（sticky / cooldown）落盘。
 *
 * 存的是**原始文本**，编解码在 `:core:lorebook` 的 `LorebookScanStateCodec`：
 * data 层不关心那串格式，它只负责"按会话 id 存一段字符串"——和预设顺序覆盖存
 * SharedPreferences 同一个判断（加列要动 schema 与迁移，而丢了只是效果提前结束）。
 */
interface SessionScanStateRepository {
    fun load(sessionId: String): String?

    fun save(sessionId: String, text: String)

    fun clear(sessionId: String)
}
