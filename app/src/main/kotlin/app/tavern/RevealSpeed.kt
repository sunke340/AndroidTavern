package app.tavern

/**
 * 流式回复的「揭示节奏」档位（口径见 `docs/plan-reveal-pacing.md`）。
 *
 * 单位是 **Unicode 码点 / 秒**，全链路统一 —— 不用 `String.length`（那是 UTF-16 单元，
 * 一个 emoji 算两个），也不用像素（需求要的是"字"的节奏，不是"高度"的节奏）。
 *
 * [Off] = 不启用游标：收到就显示，也就是这个方案之前的行为。
 * 程序**不会**自己加速、自动换档，也不设最长打印时间 —— 速度只由用户选。
 */
enum class RevealSpeed(val cps: Int, val label: String) {
    Slow(15, "慢"),
    Medium(35, "中"),
    Fast(70, "快"),
    Off(0, "不限速"),
    ;

    /** 设置里显示的当前值，例：`中 · 35 字/秒`；不限速时不带数字（那是模型决定的速度）。 */
    val summary: String
        get() = if (this == Off) "不限速" else "$label · $cps 字/秒"

    companion object {
        val Default: RevealSpeed = Medium

        /** 存的是**名字**不是序号：以后重排或插入档位，已保存的选择不会串。 */
        fun fromName(name: String?): RevealSpeed =
            entries.firstOrNull { it.name == name } ?: Default
    }
}
