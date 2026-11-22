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

    /**
     * 这张卡里被手动关掉的内嵌世界书条目（按 `character_book.entries` 下标）。
     *
     * ST 是把 `enabled` 写进卡文件里的；我们先存在应用内，**不动卡文件**——
     * 写回卡属于"卡编辑"，要单独一批（备份 + 校验）。等世界书引擎（P1）落地后再决定要不要同步回卡。
     */
    fun disabledBookEntries(cardId: String): Set<Int>

    fun saveDisabledBookEntries(cardId: String, disabled: Set<Int>)

    /** 这张卡的消息样式覆盖；null = 跟随全局默认。 */
    fun cardMessageStyle(cardId: String): String?

    fun saveCardMessageStyle(cardId: String, style: String?)

    /** 保存 API Key（加密后落盘；空串表示清除）。 */
    fun saveApiKey(key: String)
}
