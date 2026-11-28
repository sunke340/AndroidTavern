package app.tavern.data.config

import app.tavern.core.model.ConfigOverride

// 数据类本身在 `:core:model`（纯值类型，`core/preset` 的解析函数要用它）；
// 这里只留"按作用域读写"的接口与实现约束。

/** 按作用域读写覆盖：`session:<会话 id>` / `card:<卡 id>`。 */
interface ConfigOverrideRepository {
    fun load(scope: String): ConfigOverride

    fun save(scope: String, value: ConfigOverride)

    fun clear(scope: String)

    companion object {
        fun session(sessionId: String): String = "session:$sessionId"

        fun card(cardId: String): String = "card:$cardId"
    }
}
