package app.tavern.data.settings.impl

import android.content.Context
import app.tavern.data.secret.SecretStore
import app.tavern.data.secret.impl.KeystoreSecretStore
import app.tavern.data.settings.AppSettings
import app.tavern.data.settings.CardBookOverride
import app.tavern.data.settings.UiAppearance
import app.tavern.data.settings.SettingsRepository

/**
 * 普通字段（baseUrl / model）走 SharedPreferences；API Key 交给 [SecretStore] 加密。
 *
 * 为什么把"记住 Key"当回事：M1 之前 Key 只在内存，杀进程就没了，
 * 用户看到的现象是"发消息突然 401"，很难联想到是 Key 丢了。
 */
class PreferencesSettingsRepository(
    context: Context,
    private val secrets: SecretStore = KeystoreSecretStore(context),
) : SettingsRepository {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateThemeDefault()
    }

    /**
     * 一次性迁移：v1 的默认主题是森林绿，品牌色改成紫罗兰后老设备应该跟着走。
     *
     * 只搬 **Forest**（也就是旧默认值）：真选过 Amber / Slate 的用户保持原样，
     * 用 flag 保证只做一次，之后再切回森林绿不会被改回去。
     */
    private fun migrateThemeDefault() {
        if (prefs.getBoolean(KEY_THEME_MIGRATED, false)) return
        val current = prefs.getString(KEY_THEME_PRESET, null)
        val editor = prefs.edit().putBoolean(KEY_THEME_MIGRATED, true)
        if (current == null || current == "Forest") {
            editor.putString(KEY_THEME_PRESET, DEFAULT_THEME_PRESET)
        }
        editor.apply()
    }

    override fun load(): AppSettings = AppSettings(
        baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
        apiKey = runCatching { secrets.get(KEY_API_KEY) }.getOrNull().orEmpty(),
        model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
        temperature = prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE.toFloat()).toDouble(),
        maxTokens = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS),
        topP = prefs.getDoubleOrNull(KEY_TOP_P),
        frequencyPenalty = prefs.getDoubleOrNull(KEY_FREQUENCY_PENALTY),
        presencePenalty = prefs.getDoubleOrNull(KEY_PRESENCE_PENALTY),
        messageStyle = prefs.getString(KEY_MESSAGE_STYLE, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MESSAGE_STYLE,
        themePreset = prefs.getString(KEY_THEME_PRESET, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_THEME_PRESET,
        themeMode = prefs.getString(KEY_THEME_MODE, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_THEME_MODE,
        navAnimation = prefs.getString(KEY_NAV_ANIMATION, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NAV_ANIMATION,
        visualEffects = prefs.getString(KEY_VISUAL_EFFECTS, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_VISUAL_EFFECTS,
        revealSpeed = prefs.getString(KEY_REVEAL_SPEED, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_REVEAL_SPEED,
        bubbleFontSize = prefs.getFloatOrNull(KEY_BUBBLE_FONT_SIZE),
    )

    /**
     * 只读偏好的外观项。这里**故意不碰** [secrets] —— 首帧等不起 Keystore。
     * 与 [load] 用同一组默认值，所以"预读"和"正式读"不会给出两种答案。
     */
    override fun uiAppearance(): UiAppearance = UiAppearance(
        themePreset = prefs.getString(KEY_THEME_PRESET, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_THEME_PRESET,
        themeMode = prefs.getString(KEY_THEME_MODE, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_THEME_MODE,
        navAnimation = prefs.getString(KEY_NAV_ANIMATION, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NAV_ANIMATION,
        visualEffects = prefs.getString(KEY_VISUAL_EFFECTS, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_VISUAL_EFFECTS,
        messageStyle = prefs.getString(KEY_MESSAGE_STYLE, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MESSAGE_STYLE,
        bubbleFontSize = prefs.getFloatOrNull(KEY_BUBBLE_FONT_SIZE),
    )

    override fun saveConnection(baseUrl: String, model: String) {
        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_MODEL, model)
            .apply()
    }

    override fun saveSampling(
        temperature: Double?,
        maxTokens: Int?,
        topP: Double?,
        frequencyPenalty: Double?,
        presencePenalty: Double?,
    ) {
        prefs.edit().apply {
            temperature?.let { putFloat(KEY_TEMPERATURE, it.toFloat()) }
            maxTokens?.let { putInt(KEY_MAX_TOKENS, it) }
            topP?.let { putFloat(KEY_TOP_P, it.toFloat()) }
            frequencyPenalty?.let { putFloat(KEY_FREQUENCY_PENALTY, it.toFloat()) }
            presencePenalty?.let { putFloat(KEY_PRESENCE_PENALTY, it.toFloat()) }
        }.apply()
    }

    /** 没存过就是 null（= 不指定），不能拿 getFloat 的默认值顶替 —— 那会变成"发了一个 0"。 */
    private fun android.content.SharedPreferences.getDoubleOrNull(key: String): Double? =
        if (contains(key)) getFloat(key, 0f).toDouble() else null

    override fun saveBubbleFontSize(size: Float?) {
        prefs.edit().apply {
            if (size == null) remove(KEY_BUBBLE_FONT_SIZE) else putFloat(KEY_BUBBLE_FONT_SIZE, size)
        }.apply()
    }

    /** 没存过就是 null（= 标准），不能拿 getFloat 的默认值顶替。 */
    private fun android.content.SharedPreferences.getFloatOrNull(key: String): Float? =
        if (contains(key)) getFloat(key, 0f) else null

    override fun saveMessageStyle(style: String) {
        prefs.edit().putString(KEY_MESSAGE_STYLE, style).apply()
    }

    override fun saveTheme(preset: String?, mode: String?) {
        prefs.edit().apply {
            preset?.let { putString(KEY_THEME_PRESET, it) }
            mode?.let { putString(KEY_THEME_MODE, it) }
        }.apply()
    }

    override fun saveNavAnimation(name: String) {
        prefs.edit().putString(KEY_NAV_ANIMATION, name).apply()
    }

    override fun builtinRegexDisabled(): Set<String> =
        prefs.getStringSet(KEY_BUILTIN_REGEX_DISABLED, emptySet()).orEmpty().toSet()

    override fun saveBuiltinRegexDisabled(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_BUILTIN_REGEX_DISABLED, ids).apply()
    }

    override fun saveVisualEffects(name: String) {
        prefs.edit().putString(KEY_VISUAL_EFFECTS, name).apply()
    }

    override fun saveRevealSpeed(name: String) {
        prefs.edit().putString(KEY_REVEAL_SPEED, name).apply()
    }

    override fun cardBookOverride(cardId: String): CardBookOverride = CardBookOverride(
        off = prefs.getStringSet(KEY_CARD_BOOK_OFF_PREFIX + cardId, emptySet()).orEmpty().toIntSet(),
        forcedOn = prefs.getStringSet(KEY_CARD_BOOK_FORCED_PREFIX + cardId, emptySet()).orEmpty().toIntSet(),
    )

    override fun saveCardBookOverride(cardId: String, override: CardBookOverride) {
        prefs.edit()
            .putStringSet(KEY_CARD_BOOK_OFF_PREFIX + cardId, override.off.toStringSet())
            .putStringSet(KEY_CARD_BOOK_FORCED_PREFIX + cardId, override.forcedOn.toStringSet())
            .apply()
    }

    override fun legacyBookDisabledIndexes(cardId: String): Set<Int> =
        prefs.getStringSet(KEY_BOOK_DISABLED_PREFIX + cardId, emptySet()).orEmpty().toIntSet()

    override fun clearLegacyBookDisabledIndexes(cardId: String) {
        prefs.edit().remove(KEY_BOOK_DISABLED_PREFIX + cardId).apply()
    }

    override fun cardMessageStyle(cardId: String): String? =
        prefs.getString(KEY_CARD_STYLE_PREFIX + cardId, null)

    override fun saveCardMessageStyle(cardId: String, style: String?) {
        prefs.edit().apply {
            if (style.isNullOrBlank()) remove(KEY_CARD_STYLE_PREFIX + cardId)
            else putString(KEY_CARD_STYLE_PREFIX + cardId, style)
        }.apply()
    }

    override fun saveApiKey(key: String) {
        if (key.isBlank()) {
            secrets.remove(KEY_API_KEY)
        } else {
            secrets.put(KEY_API_KEY, key)
        }
    }

    private companion object {
        const val KEY_BUILTIN_REGEX_DISABLED = "builtin_regex_disabled"
        const val PREFS = "tavern.settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "top_p"
        const val KEY_FREQUENCY_PENALTY = "frequency_penalty"
        const val KEY_PRESENCE_PENALTY = "presence_penalty"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_MESSAGE_STYLE = "message_style"
        const val KEY_THEME_PRESET = "theme_preset"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_NAV_ANIMATION = "nav_animation"
        const val KEY_VISUAL_EFFECTS = "visual_effects"
        const val KEY_REVEAL_SPEED = "reveal_speed"
        const val KEY_BUBBLE_FONT_SIZE = "bubble_font_size"
        const val KEY_THEME_MIGRATED = "theme_migrated_violet"
        // 老版本按下标存的"关掉"集合，只用于一次性迁移到 uid
        const val KEY_BOOK_DISABLED_PREFIX = "book_disabled:"
        const val KEY_CARD_BOOK_OFF_PREFIX = "card_book_off:"
        const val KEY_CARD_BOOK_FORCED_PREFIX = "card_book_forced:"
        const val KEY_CARD_STYLE_PREFIX = "card_style:"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_TEMPERATURE = 1.0
        const val DEFAULT_MAX_TOKENS = 0
        const val DEFAULT_MESSAGE_STYLE = "Card"
        const val DEFAULT_THEME_PRESET = "Violet"
        const val DEFAULT_THEME_MODE = "Follow"
        const val DEFAULT_NAV_ANIMATION = "Slide"
        const val DEFAULT_VISUAL_EFFECTS = "Off"
        const val DEFAULT_REVEAL_SPEED = "Medium"
    }
}

/** 偏好里存的是字符串集合（`StringSet` 不能放 Int）。 */
private fun Set<String>.toIntSet(): Set<Int> = mapNotNull { it.toIntOrNull() }.toSet()

private fun Set<Int>.toStringSet(): Set<String> = map { it.toString() }.toSet()

