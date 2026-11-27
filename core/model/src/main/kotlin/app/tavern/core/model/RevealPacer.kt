package app.tavern.core.model

/**
 * 「揭示节奏」的推进器：只管"这一帧该显示到第几个码点"。
 *
 * 从 `ChatScreen` 的 `withFrameNanos` 循环里抽出来，是为了**能单测** —— 这段数学有三个
 * 容易被改坏的地方：追平时不能累计时间（否则新 delta 一到就暴吐）、停顿恢复要丢小数余量、
 * 单帧最多按 50ms 计（长时间掉帧时"宁可慢也不暴吐"）。本机编不了 `:app`，只能把它放在这。
 *
 * 口径见 `docs/plan-reveal-pacing.md` §3.3。
 */
public class RevealPacer {
    /** 攒着的小数码点余量（`< 1`）。跨"追平"保留；只在暂停恢复与 [reset] 时清零。 */
    public var pending: Float = 0f
        private set

    // 用 -1 当"还没立基准"：0 是合法的帧时间（测试里就从 0 开始），拿 0 当哨兵会永远判成未初始化
    private var lastTickNanos: Long = -1L
    private var lastAdvanceNanos: Long = -1L

    /** 建游标时调用：下一帧只用来立基准。 */
    public fun reset() {
        pending = 0f
        lastTickNanos = -1L
        lastAdvanceNanos = -1L
    }

    /** 是否还没立过基准（第一帧）。 */
    public fun needsBaseline(): Boolean = lastTickNanos < 0L

    /** 立基准：把"现在"当成计时起点，不推进。 */
    public fun markBaseline(nowNanos: Long) {
        lastTickNanos = nowNanos
        lastAdvanceNanos = nowNanos
    }

    /** 这一帧什么都不能动（例如收缩窗口期）：只更新时间基准。 */
    public fun idle(nowNanos: Long) {
        lastTickNanos = nowNanos
    }

    /**
     * 推进一帧。
     *
     * @param ratePerSec 目标速率（码点/秒），**每帧现读**，所以换档天然生效
     * @param revealed 当前已显示的码点数
     * @param received 已收到的码点数
     * @return 这一帧之后应该显示的码点数（只增不减，且不超过 [received]）
     */
    public fun step(nowNanos: Long, ratePerSec: Float, revealed: Int, received: Int): Int {
        if (lastTickNanos < 0L) {
            markBaseline(nowNanos)
            return revealed
        }
        val rawDt = nowNanos - lastTickNanos
        lastTickNanos = nowNanos
        if (revealed >= received) {
            // 追平：不累计时间、不写状态、保留小数余量（否则下一秒来字就整段冒出来）
            //
            // 同时把**停滞计时器洗到此刻**：没有账可还的那段时间不算停滞。否则"等模型首个
            // token 的几秒"会被看门狗算成停滞，首字一到就整轮补齐 —— 2026-11-27 真机 v181：
            // 首条回复就是这么被一次补齐、退回旧动画的（日志 `打印停滞，补齐 剩余=1cp 已等=5.6s`）。
            lastAdvanceNanos = nowNanos
            return revealed
        }
        if (rawDt > PAUSE_GAP_NANOS) {
            // 停顿太久（模型在思考 / 页面进后台）：当"暂停恢复"，丢掉余量但不补齐
            pending = 0f
            lastAdvanceNanos = nowNanos
            return revealed
        }
        val dt = minOf(rawDt, MAX_TICK_NANOS)
        pending += ratePerSec * dt / 1_000_000_000f
        val n = pending.toInt()
        if (n <= 0) return revealed
        pending -= n
        val next = minOf(revealed + n, received)
        if (next > revealed) lastAdvanceNanos = nowNanos
        return next
    }

    /** 距上次真正前进过了多久（看门狗用）。 */
    public fun stalledFor(nowNanos: Long): Long =
        if (lastAdvanceNanos < 0L) 0L else nowNanos - lastAdvanceNanos

    public companion object {
        /** 一次停顿超过它就算"暂停恢复"。 */
        public const val PAUSE_GAP_NANOS: Long = 1_000_000_000L

        /** 单帧最多按 50ms 计入额度。 */
        public const val MAX_TICK_NANOS: Long = 50_000_000L
    }
}
