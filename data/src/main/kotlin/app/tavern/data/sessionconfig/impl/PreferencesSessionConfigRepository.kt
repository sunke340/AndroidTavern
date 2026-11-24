package app.tavern.data.sessionconfig.impl

import android.content.Context
import app.tavern.data.sessionconfig.SessionConfig
import app.tavern.data.sessionconfig.SessionConfigRepository

/**
 * 会话配置存 SharedPreferences：一个会话一个 key，值是几行 `字段=值`。
 *
 * 为什么不用数据库：这里存的是"这台设备上偏向怎么聊"的选择，不是聊天内容。
 * 加一列要动 schema 与迁移（TavernDatabase 现在 v2），而这条数据丢了只影响"默认值"，
 * 不值得为它付一次迁移的代价 —— 和预设顺序覆盖存 SharedPreferences 是同一个判断。
 *
 * 为什么不用 JSON：字段就两三个，`k=v` 一行一个，读写都不需要解析器；
 * 值里出现换行只可能来自模型档案 id（我们自己生成的 uuid），不会带换行。
 */
class PreferencesSessionConfigRepository(context: Context) : SessionConfigRepository {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(sessionId: String): SessionConfig {
        val raw = prefs.getString(sessionId, null) ?: return SessionConfig()
        var profileId: String? = null
        var effort: String? = null
        raw.lineSequence().forEach { line ->
            val at = line.indexOf('=')
            if (at <= 0) return@forEach
            val value = line.substring(at + 1).ifBlank { null }
            when (line.substring(0, at)) {
                KEY_PROFILE -> profileId = value
                KEY_EFFORT -> effort = value
            }
        }
        return SessionConfig(profileId = profileId, reasoningEffort = effort)
    }

    override fun save(sessionId: String, config: SessionConfig) {
        val text = buildString {
            config.profileId?.let { append(KEY_PROFILE).append('=').append(it).append('\n') }
            config.reasoningEffort?.let { append(KEY_EFFORT).append('=').append(it).append('\n') }
        }
        if (text.isEmpty()) {
            // 全跟随 = 没有覆盖，别在盘上留空串（免得"看起来有配置"）
            prefs.edit().remove(sessionId).apply()
        } else {
            prefs.edit().putString(sessionId, text).apply()
        }
    }

    override fun clear(sessionId: String) {
        prefs.edit().remove(sessionId).apply()
    }

    private companion object {
        const val PREFS = "session_config"
        const val KEY_PROFILE = "profileId"
        const val KEY_EFFORT = "effort"
    }
}
