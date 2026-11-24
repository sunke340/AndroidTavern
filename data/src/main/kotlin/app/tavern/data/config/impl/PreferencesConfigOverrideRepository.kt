package app.tavern.data.config.impl

import android.content.Context
import app.tavern.data.config.ConfigOverride
import app.tavern.data.config.ConfigOverrideRepository

/**
 * 覆盖存 SharedPreferences：一个作用域一个 key，值是几行 `字段=值`，集合用 `\u001F` 分隔。
 *
 * 为什么不用数据库：这里存的是"这台设备上偏向怎么聊"的选择，不是聊天内容。加列要动 schema
 * 与迁移（TavernDatabase 现在 v2），而这条数据丢了只影响"默认值"—— 和预设顺序覆盖同一个判断。
 *
 * 为什么用 `\u001F` 当分隔符而不是逗号：世界书文件名是用户起的，完全可能带逗号。
 * 这个控制字符不会出现在文件名里，也不需要转义。
 */
class PreferencesConfigOverrideRepository(context: Context) : ConfigOverrideRepository {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(scope: String): ConfigOverride {
        val raw = prefs.getString(scope, null) ?: return ConfigOverride()
        var profileId: String? = null
        var effort: String? = null
        var preset: String? = null
        var books: Set<String>? = null
        var regex: Set<String>? = null
        var greeting: Int? = null
        raw.lineSequence().forEach { line ->
            val at = line.indexOf('=')
            if (at <= 0) return@forEach
            val value = line.substring(at + 1)
            when (line.substring(0, at)) {
                KEY_PROFILE -> profileId = value.ifBlank { null }
                KEY_EFFORT -> effort = value.ifBlank { null }
                KEY_PRESET -> preset = value.ifBlank { null }
                // 空串 = 显式空集（"全都不要"），null 才是跟随
                KEY_BOOKS -> books = value.split(SEP).filter { it.isNotEmpty() }.toSet()
                KEY_REGEX -> regex = value.split(SEP).filter { it.isNotEmpty() }.toSet()
                KEY_GREETING -> greeting = value.toIntOrNull()
            }
        }
        return ConfigOverride(
            profileId = profileId,
            reasoningEffort = effort,
            presetName = preset,
            worldBookNames = books,
            regexIds = regex,
            greetingIndex = greeting,
        )
    }

    override fun save(scope: String, value: ConfigOverride) {
        if (value.isEmpty) {
            prefs.edit().remove(scope).apply()
            return
        }
        val text = buildString {
            value.profileId?.let { append(KEY_PROFILE).append('=').append(it).append('\n') }
            value.reasoningEffort?.let { append(KEY_EFFORT).append('=').append(it).append('\n') }
            value.presetName?.let { append(KEY_PRESET).append('=').append(it).append('\n') }
            // 空集要写出来（"=空"），否则读回来就变成"跟随"了
            value.worldBookNames?.let {
                append(KEY_BOOKS).append('=').append(it.joinToString(SEP)).append('\n')
            }
            value.regexIds?.let {
                append(KEY_REGEX).append('=').append(it.joinToString(SEP)).append('\n')
            }
            value.greetingIndex?.let { append(KEY_GREETING).append('=').append(it).append('\n') }
        }
        prefs.edit().putString(scope, text).apply()
    }

    override fun clear(scope: String) {
        prefs.edit().remove(scope).apply()
    }

    private companion object {
        const val PREFS = "config_override"

        /** 单元分隔符：文件名里不会出现，不用转义。 */
        const val SEP = "\u001F"

        const val KEY_PROFILE = "profileId"
        const val KEY_EFFORT = "effort"
        const val KEY_PRESET = "preset"
        const val KEY_BOOKS = "books"
        const val KEY_REGEX = "regex"
        const val KEY_GREETING = "greeting"
    }
}
