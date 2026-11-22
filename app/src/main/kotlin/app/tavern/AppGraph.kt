package app.tavern

import android.content.Context
import app.tavern.data.cards.CardRepository
import app.tavern.data.cards.impl.CardStore
import app.tavern.data.chat.ChatRepository
import app.tavern.data.chat.impl.SqliteChatRepository
import app.tavern.data.profiles.ProfileRepository
import app.tavern.data.profiles.impl.SqliteProfileRepository
import app.tavern.data.settings.SettingsRepository
import app.tavern.data.settings.impl.PreferencesSettingsRepository

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

    /** 模型档案（多套连接配置）。Key 仍由它自己的 SecretStore 管，别在这里碰明文。 */
    fun profiles(context: Context): ProfileRepository =
        profileStore ?: synchronized(this) {
            profileStore ?: SqliteProfileRepository(context.applicationContext)
                .also { profileStore = it }
        }
}
