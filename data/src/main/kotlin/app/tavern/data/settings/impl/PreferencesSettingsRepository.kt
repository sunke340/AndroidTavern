package app.tavern.data.settings.impl

import android.content.Context
import app.tavern.data.secret.SecretStore
import app.tavern.data.secret.impl.KeystoreSecretStore
import app.tavern.data.settings.AppSettings
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

    override fun load(): AppSettings = AppSettings(
        baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
        apiKey = runCatching { secrets.get(KEY_API_KEY) }.getOrNull().orEmpty(),
        model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
    )

    override fun saveConnection(baseUrl: String, model: String) {
        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_MODEL, model)
            .apply()
    }

    override fun saveApiKey(key: String) {
        if (key.isBlank()) {
            secrets.remove(KEY_API_KEY)
        } else {
            secrets.put(KEY_API_KEY, key)
        }
    }

    private companion object {
        const val PREFS = "tavern.settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}
