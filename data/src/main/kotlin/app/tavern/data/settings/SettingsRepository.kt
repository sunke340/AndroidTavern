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

    /** 保存 API Key（加密后落盘；空串表示清除）。 */
    fun saveApiKey(key: String)
}
