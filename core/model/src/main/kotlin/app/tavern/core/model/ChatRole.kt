package app.tavern.core.model

/**
 * OpenAI 兼容消息的角色。
 *
 * 取值与原版发给 `/chat/completions` 的 `role` 字段一一对应——这里不做任何"更合理"的抽象，
 * 因为这一层就是给夹具比对用的，任何改写都会让断言失去意义。
 */
public enum class ChatRole(
    public val wire: String,
) {
    System("system"),
    User("user"),
    Assistant("assistant"),
    ;

    public companion object {
        public fun fromWire(value: String): ChatRole =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知的 role: $value")
    }
}
