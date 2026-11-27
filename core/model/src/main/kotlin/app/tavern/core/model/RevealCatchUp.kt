package app.tavern.core.model

/**
 * 点气泡"快进"的速率（2026-11-27 真机 v183 的教训）。
 *
 * 第一版是每帧现算「**当前**欠账 / 0.35s」。模型还在吐字时这会退化成实时打印：
 * 欠账永远清不到 0，速率停在"刚好追上供给"的水平 —— 日志里 `目标速率=437 → 194 → 188cp/s`，
 * 而档位是 15cp/s，用户看到的就是"刷完之后变成实时打印了"。
 *
 * 正解：只认**点下去那一刻**的欠账，算出一个**固定**速率，追平（按那一刻的欠账量判定）即回档位。
 * 后来到的字跟普通打印一样排队，按档位走。
 *
 * 为什么不是"剩余量 / 时长"每秒重算：那是一个反馈环 —— 供给不停，反馈环的平衡点就不是 0。
 */
public object RevealCatchUp {
    /** 目标时长（秒）：点一下之后大约这么久把"已有的"刷完。 */
    public const val Seconds: Float = 0.5f

    /**
     * 速率上限（码点/秒）。
     *
     * 欠账特别大（几千字）时也别做成一帧糊上去 —— 那又回到"没有动画"了。
     * 2500 大约相当于 60fps 下每帧 40 个字，是"看得见的一段快速生长"。
     */
    public const val MaxRate: Float = 2500f

    /** 按**点下去那一刻**的欠账算出固定速率；欠账为 0 时返回 0（调用方据此不启动快进）。 */
    public fun rateFor(backlogAtTap: Int): Float {
        if (backlogAtTap <= 0) return 0f
        return (backlogAtTap / Seconds).coerceAtMost(MaxRate)
    }
}
