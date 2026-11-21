package app.tavern

import android.content.Context
import app.tavern.data.cards.CardRepository
import app.tavern.data.cards.impl.CardStore

/**
 * 手写的依赖对象图（[app/AGENTS.md](../../AGENTS.md)：先手写，别上注解处理）。
 *
 * 现在只有卡片仓储一个东西，值不值得这么正式？值得：ViewModel 不该自己 `new` 仓储，
 * 否则以后加 Room、加 Key 存储会各建各的实例，SharedPreferences 与文件状态就分叉了。
 */
object AppGraph {

    @Volatile
    private var cardStore: CardRepository? = null

    fun cards(context: Context): CardRepository =
        cardStore ?: synchronized(this) {
            cardStore ?: CardStore(context.applicationContext).also { cardStore = it }
        }
}
