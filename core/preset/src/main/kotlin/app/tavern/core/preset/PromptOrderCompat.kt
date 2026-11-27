package app.tavern.core.preset

/**
 * 预设顺序的**向前兼容**补齐。
 *
 * 为什么要补：`personaDescription` 是 2026-11-27 这一批才接进来的格子。用户之前导入的预设
 * （或更早存下的顺序覆盖）里根本没有这一格 —— 原版的判据是"表里没有就不发"，
 * 于是人设明明选好了，请求里一个字都没有：真机日志 `人设=卡尔(描述=6字)` 而 `messages`
 * 条数与"没选人设"时一模一样。
 *
 * 口径（三条都要守住）：
 * - 已经**有**这一格 → 原样返回。`enabled = false` 是用户显式关掉，**尊重**，不强行打开；
 * - 没有 → 插在 `worldInfoBefore` 之后（内置默认表就在这个位置），找不到 `worldInfoBefore` 就放最前；
 * - 顺序为空（还没加载到）→ 原样返回，别凭空造一张表。
 *
 * 这是**故意偏离原版**的一条：原版不会替旧预设补格子。理由写在
 * `docs/dev/original-behavior.md`「我们主动偏离的地方」。
 */
public object PromptOrderCompat {
    /** 人设描述那一格的 identifier（原版 `PromptManager.js`）。 */
    public const val PERSONA_DESCRIPTION: String = "personaDescription"

    private const val WORLD_INFO_BEFORE: String = "worldInfoBefore"

    /** 补齐"人设描述"那一格；已经有了（哪怕被关掉）就原样返回。 */
    public fun ensurePersonaSlot(order: List<PromptOrderEntry>): List<PromptOrderEntry> {
        if (order.isEmpty()) return order
        if (order.any { it.identifier == PERSONA_DESCRIPTION }) return order
        val anchor = order.indexOfFirst { it.identifier == WORLD_INFO_BEFORE }
        val insertAt = if (anchor >= 0) anchor + 1 else 0
        return order.toMutableList().apply { add(insertAt, PromptOrderEntry(PERSONA_DESCRIPTION)) }
    }
}
