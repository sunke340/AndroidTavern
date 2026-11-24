package app.tavern.data.settings

/** 连接配置。[apiKey] 是机密，走 Keystore 加密存储；其余是普通偏好。 */
data class AppSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double,
    /** 0 表示不限（不往请求里放 max_tokens）。 */
    val maxTokens: Int,
    /** 消息展示样式，见 app 层 `MessageStyle`（这里只存字符串，data 不关心 UI 枚举）。 */
    val messageStyle: String,
    /** 主题预设名（如 `Forest`）与深浅模式（`Follow` / `Light` / `Dark`）。 */
    val themePreset: String,
    val themeMode: String,
    /** 二级页转场风格，取值见 app 层 `NavAnimation`（这里只存字符串，data 不关心 UI 枚举）。 */
    val navAnimation: String,
    /** 视觉效果档位，取值见 app 层 `VisualEffects`（`Glass` / `Off`）。 */
    val visualEffects: String,
)

/**
 * 设置存取。`feature`/`app` 只依赖这个接口。
 *
 * 为什么 Key 单独一个方法：它走加密路径，别的字段走普通 SharedPreferences，
 * 调用方不该看见这个区别，但实现里必须分开。
 */
interface SettingsRepository {
    fun load(): AppSettings

    /** 保存非机密字段（baseUrl / model）。 */
    fun saveConnection(baseUrl: String, model: String)

    /** 保存采样参数。传 null 表示"这一项不动"。 */
    fun saveSampling(temperature: Double?, maxTokens: Int?)

    /** 保存消息展示样式（字符串，由 UI 层定义取值）。 */
    fun saveMessageStyle(style: String)

    /** 保存主题；传 null 表示这一项不动。 */
    fun saveTheme(preset: String?, mode: String?)

    /** 保存二级页转场风格（字符串，由 UI 层定义取值）。 */
    fun saveNavAnimation(name: String)

    /** 保存视觉效果档位（字符串，由 UI 层定义取值）。 */
    fun saveVisualEffects(name: String)

    /**
     * 这张卡的内嵌世界书开关覆盖（按条目 **uid**，不是下标 —— 卡在 PC 上改过内嵌书再导回来，
     * 下标会整体错位，uid 不会）。
     *
     * ST 是把 `enabled` 写进卡文件里的；我们只存在应用内，**不动卡文件**——
     * 写回卡属于"卡编辑"，要单独一批（备份 + 校验）。
     */
    fun cardBookOverride(cardId: String): CardBookOverride

    fun saveCardBookOverride(cardId: String, override: CardBookOverride)

    /** 老版本按下标存的"关掉"集合：只用来迁移到 uid，迁完就清掉。 */
    fun legacyBookDisabledIndexes(cardId: String): Set<Int>

    fun clearLegacyBookDisabledIndexes(cardId: String)

    /** 这张卡的消息样式覆盖；null = 跟随全局默认。 */
    fun cardMessageStyle(cardId: String): String?

    fun saveCardMessageStyle(cardId: String, style: String?)

    /** 保存 API Key（加密后落盘；空串表示清除）。 */
    fun saveApiKey(key: String)
}

/**
 * 卡内嵌世界书的本地覆盖。两个集合互斥：
 * - [off]：卡里是**开**着、应用内关掉的；
 * - [forcedOn]：卡里是**关**着、应用内强制开启的（界面上整行标红）。
 */
data class CardBookOverride(
    val off: Set<Int> = emptySet(),
    val forcedOn: Set<Int> = emptySet(),
)
