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
        var builtin: Set<String>? = null
        var temperature: Double? = null
        var maxTokens: Int? = null
        var topP: Double? = null
        var freq: Double? = null
        var presence: Double? = null
        var bubbleFontSize: Float? = null
        var persona: String? = null
        var anText: String? = null
        var anDepth: Int? = null
        var anRole: String? = null
        var anInterval: Int? = null
        var anPosition: Int? = null
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
                KEY_PERSONA -> persona = value.ifBlank { null }
                KEY_AN_TEXT -> anText = value.replace("\\n", "\n").ifBlank { null }
                KEY_AN_DEPTH -> anDepth = value.toIntOrNull()
                KEY_AN_ROLE -> anRole = value.ifBlank { null }
                KEY_AN_INTERVAL -> anInterval = value.toIntOrNull()
                KEY_AN_POSITION -> anPosition = value.toIntOrNull()
                KEY_BUILTIN -> builtin = value.split(SEP).filter { it.isNotEmpty() }.toSet()
                KEY_TEMPERATURE -> temperature = value.toDoubleOrNull()
                KEY_MAX_TOKENS -> maxTokens = value.toIntOrNull()
                KEY_TOP_P -> topP = value.toDoubleOrNull()
                KEY_FREQ_PENALTY -> freq = value.toDoubleOrNull()
                KEY_PRESENCE_PENALTY -> presence = value.toDoubleOrNull()
                KEY_BUBBLE_FONT_SIZE -> bubbleFontSize = value.toFloatOrNull()
            }
        }
        return ConfigOverride(
            profileId = profileId,
            reasoningEffort = effort,
            presetName = preset,
            worldBookNames = books,
            regexIds = regex,
            builtinDisabled = builtin,
            greetingIndex = greeting,
            personaId = persona,
            authorNote = anText,
            authorNoteDepth = anDepth,
            authorNoteRole = anRole,
            authorNoteInterval = anInterval,
            authorNotePosition = anPosition,
            temperature = temperature,
            maxTokens = maxTokens,
            topP = topP,
            frequencyPenalty = freq,
            presencePenalty = presence,
            bubbleFontSize = bubbleFontSize,
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
            value.personaId?.let { append(KEY_PERSONA).append('=').append(it).append('\n') }
            // 作者注的正文可能有换行：编码时换行 → \\n，读的时候换回来
            value.authorNote?.let {
                append(KEY_AN_TEXT).append('=').append(it.replace("\n", "\\n")).append('\n')
            }
            value.authorNoteDepth?.let { append(KEY_AN_DEPTH).append('=').append(it).append('\n') }
            value.authorNoteRole?.let { append(KEY_AN_ROLE).append('=').append(it).append('\n') }
            value.authorNoteInterval?.let { append(KEY_AN_INTERVAL).append('=').append(it).append('\n') }
            value.authorNotePosition?.let { append(KEY_AN_POSITION).append('=').append(it).append('\n') }
            // 内置正则的开关集合：和世界书/正则一样，空集要写出来（"明确全开"≠跟随）
            value.builtinDisabled?.let {
                append(KEY_BUILTIN).append('=').append(it.joinToString(SEP)).append('\n')
            }
            // 采样五项：只写非 null 的（null = 跟随上级）
            value.temperature?.let { append(KEY_TEMPERATURE).append('=').append(it).append('\n') }
            value.maxTokens?.let { append(KEY_MAX_TOKENS).append('=').append(it).append('\n') }
            value.topP?.let { append(KEY_TOP_P).append('=').append(it).append('\n') }
            value.frequencyPenalty?.let { append(KEY_FREQ_PENALTY).append('=').append(it).append('\n') }
            value.presencePenalty?.let { append(KEY_PRESENCE_PENALTY).append('=').append(it).append('\n') }
            value.bubbleFontSize?.let { append(KEY_BUBBLE_FONT_SIZE).append('=').append(it).append('\n') }
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
        const val KEY_PERSONA = "persona"
        const val KEY_AN_TEXT = "an_text"
        const val KEY_AN_DEPTH = "an_depth"
        const val KEY_AN_ROLE = "an_role"
        const val KEY_AN_INTERVAL = "an_interval"
        const val KEY_AN_POSITION = "an_position"
        const val KEY_GREETING = "greeting"
        const val KEY_BUILTIN = "builtin_disabled"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_TOP_P = "top_p"
        const val KEY_FREQ_PENALTY = "frequency_penalty"
        const val KEY_PRESENCE_PENALTY = "presence_penalty"
        const val KEY_BUBBLE_FONT_SIZE = "bubble_font_size"
    }
}
