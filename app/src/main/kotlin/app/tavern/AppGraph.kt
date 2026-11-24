package app.tavern

import android.content.Context
import app.tavern.data.cards.CardRepository
import app.tavern.data.cards.impl.CardStore
import app.tavern.data.chat.ChatRepository
import app.tavern.data.chat.impl.SqliteChatRepository
import app.tavern.data.presets.PresetRepository
import app.tavern.data.presets.impl.FilePresetRepository
import app.tavern.data.profiles.ProfileRepository
import app.tavern.data.profiles.impl.SqliteProfileRepository
import app.tavern.data.regex.RegexRepository
import app.tavern.data.regex.impl.PreferencesRegexRepository
import app.tavern.data.config.ConfigOverrideRepository
import app.tavern.data.config.impl.PreferencesConfigOverrideRepository
import app.tavern.data.sessionstate.SessionScanStateRepository
import app.tavern.data.sessionstate.impl.PreferencesSessionScanStateRepository
import app.tavern.data.settings.SettingsRepository
import app.tavern.data.settings.impl.PreferencesSettingsRepository
import app.tavern.data.worldinfo.WorldBookRepository
import app.tavern.data.worldinfo.impl.FileWorldBookRepository

/**
 * 手写的依赖对象图（[app/AGENTS.md](../../AGENTS.md)：先手写，别上注解处理）。
 *
 * ViewModel 不该自己 `new` 仓储：否则以后加 Key 存储、加预设会各建各的实例，
 * SharedPreferences 与文件状态就分叉了。
 */
object AppGraph {

    @Volatile
    private var cardStore: CardRepository? = null

    @Volatile
    private var chatStore: ChatRepository? = null

    @Volatile
    private var settingsStore: SettingsRepository? = null

    @Volatile
    private var profileStore: ProfileRepository? = null

    @Volatile
    private var worldBookStore: WorldBookRepository? = null

    @Volatile
    private var regexStore: RegexRepository? = null

    @Volatile
    private var presetStore: PresetRepository? = null

    @Volatile
    private var configOverrideStore: ConfigOverrideRepository? = null

    @Volatile
    private var scanStateStore: SessionScanStateRepository? = null

    fun cards(context: Context): CardRepository =
        cardStore ?: synchronized(this) {
            cardStore ?: CardStore(context.applicationContext).also { cardStore = it }
        }

    fun chats(context: Context): ChatRepository =
        chatStore ?: synchronized(this) {
            chatStore ?: SqliteChatRepository(context.applicationContext).also { chatStore = it }
        }

    fun settings(context: Context): SettingsRepository =
        settingsStore ?: synchronized(this) {
            settingsStore ?: PreferencesSettingsRepository(context.applicationContext)
                .also { settingsStore = it }
        }

    /** 全局世界书（独立的 .json 文件，不是卡内嵌书）。 */
    fun worldBooks(context: Context): WorldBookRepository =
        worldBookStore ?: synchronized(this) {
            worldBookStore ?: FileWorldBookRepository(context.applicationContext)
                .also { worldBookStore = it }
        }

    /** 正则脚本（设置→正则）。 */
    fun regex(context: Context): RegexRepository =
        regexStore ?: synchronized(this) {
            regexStore ?: PreferencesRegexRepository(context.applicationContext).also { regexStore = it }
        }

    /** 预设（设置→预设）：导入的 .json + 当前选中的那份。 */
    fun presets(context: Context): PresetRepository =
        presetStore ?: synchronized(this) {
            presetStore ?: FilePresetRepository(context.applicationContext).also { presetStore = it }
        }

    /** 配置覆盖（三级继承）：会话与角色卡两个作用域，同一个偏好文件。 */
    fun configOverrides(context: Context): ConfigOverrideRepository =
        configOverrideStore ?: synchronized(this) {
            configOverrideStore ?: PreferencesConfigOverrideRepository(context.applicationContext)
                .also { configOverrideStore = it }
        }

    /** 世界书计时状态（sticky / cooldown）：按会话 id 存一段文本。 */
    fun sessionScanStates(context: Context): SessionScanStateRepository =
        scanStateStore ?: synchronized(this) {
            scanStateStore ?: PreferencesSessionScanStateRepository(context.applicationContext)
                .also { scanStateStore = it }
        }

    /** 模型档案（多套连接配置）。Key 仍由它自己的 SecretStore 管，别在这里碰明文。 */
    fun profiles(context: Context): ProfileRepository =
        profileStore ?: synchronized(this) {
            profileStore ?: SqliteProfileRepository(context.applicationContext)
                .also { profileStore = it }
        }
}
