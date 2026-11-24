package app.tavern.data.sessionconfig

/**
 * 一条会话的配置覆盖（三级继承的最内层：会话 → 角色卡 → 全局）。
 *
 * 每个字段 `null` = **跟随上级**，非 null = 这一层自己指定。这样"跟随"和"显式选了默认值"
 * 是两种状态，不会因为"用户选的那项恰好等于全局"就被当成跟随。
 *
 * 只存"这台设备上的选择"：不改卡文件（写回 PNG 要备份与校验，是另一批），也不改全局配置。
 */
data class SessionConfig(
    /** 本会话用哪个模型档案；null = 跟随（角色卡 → 全局默认档案）。 */
    val profileId: String? = null,
    /**
     * 本会话的思考强度：null = 跟随档案；`"off"` = 关掉（不发 reasoning_effort）；
     * 其余就是原样发出去的值（low / medium / high）。
     */
    val reasoningEffort: String? = null,
)

/** 会话级配置的读写。按会话 id 存，删会话时顺手清掉。 */
interface SessionConfigRepository {
    fun load(sessionId: String): SessionConfig

    fun save(sessionId: String, config: SessionConfig)

    fun clear(sessionId: String)
}
