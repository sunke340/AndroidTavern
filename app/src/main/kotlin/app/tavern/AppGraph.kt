package app.tavern

import android.content.Context
import app.tavern.data.cards.CardRepository
import app.tavern.data.cards.impl.CardStore
import app.tavern.data.chat.ChatRepository
import app.tavern.data.chat.impl.SqliteChatRepository

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

    fun cards(context: Context): CardRepository =
        cardStore ?: synchronized(this) {
            cardStore ?: CardStore(context.applicationContext).also { cardStore = it }
        }

    fun chats(context: Context): ChatRepository =
        chatStore ?: synchronized(this) {
            chatStore ?: SqliteChatRepository(context.applicationContext).also { chatStore = it }
        }
}
