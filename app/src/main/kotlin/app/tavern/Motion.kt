package app.tavern

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * 动效 tokens。
 *
 * 三条规矩：
 * 1. 只做**帮助理解状态变化**的动画（内容换了、消息来了、按钮变成了另一个动作），不做装饰；
 * 2. 时长压在 120–240ms：手机上超过 300ms 会显得"卡"；
 * 3. 缓冲曲线统一，不要各处各写一个。
 *
 * **物理弹簧（下面三个）不遵守第 2 条**：弹簧没有时长，停下来的快慢由刚度决定。
 * 用户连续按同一个按钮时，弹簧会从当前速度接着走；`tween` 会把速度清 0 重新起跑 —— 那一下
 * 就是"不跟手"的来源（2026-11-23 用户实测反馈：快速连按时像卡了一下）。
 */
object Motion {
    /** 次要反馈：淡出、颜色变化 */
    const val Fast = 120

    /** 主要过渡：页面切换、消息入场 */
    const val Base = 180

    /**
     * 位移较大的过渡：整页滑动、列表重排。
     *
     * 240 → **280**（2026-11-23）：曲线从强 ease-out 换成 ease-out-quad 之后（运动摊到了整段时长里），
     * 用户第一版仍觉得"可以再加一点时长"。280 仍在 Emil 的"UI < 300ms"之内，
     * 换成 ease-out-quad 后约 180ms 视觉走完。**再嫌慢就上调这里，不要动曲线**（曲线再缓会起步发木）。
     */
    const val Slow = 280

    /**
     * 聊天输入区展开 / 收起。
     *
     * 用户 2026-11-25 第 2 条：按 180ms 走"看不出变形，只看到突然变大"；
     * 320ms 之后仍然嫌快，再提到 400 / 300。这道形变本身就是用户要看的东西，
     * 比常规过渡长一档；别的动画别跟着用这两个数。
     */
    const val InputExpand = 400
    const val InputCollapse = 300

    /** 标准曲线：快出慢入，Material 的 emphasized 近似 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * 减速：用于"新内容出现"（进入、淡入）。
     *
     * **ease-out-quad**（`cubic-bezier(0.25, 0.46, 0.45, 0.94)`）。
     *
     * 这里换过两次，每次都是因为"看起来太快/太慢"，所以把实测数据写在下面（240ms 的翻页，
     * 数字 = 该时刻已经走完全程的百分比）：
     *
     * | 曲线 | 10% 时间 | 25% | 50% | 75% | 视觉上走完 |
     * |---|---|---|---|---|---|
     * | `(0,0,0,1)` 极端减速 | — | — | — | — | 起步斜率无穷大，读成"弹射" |
     * | `(0.23,1,0.32,1)` 强 ease-out | 40% | 78% | 97% | 100% | ~75ms（240ms 的 1/3） |
     * | **`(0.25,0.46,0.45,0.94)` 现在** | 19% | 45% | 77% | 94% | ~155ms |
     *
     * 强 ease-out 在数学上没错（Emil 的表也推荐它），但它把 40% 的位移塞进前 10% 的时间，
     * 用户的原话是"翻页有点太快了"—— 那 240ms 其实只用掉了前 75ms，剩下全是几乎不动的尾巴。
     * 现在这条把运动摊到整段时长里：前半段还在明显滑、后半段收住。
     * **再嫌快就动 [Slow]（240→280），不要再往缓的方向换曲线** —— 再缓就变成"起步发木"。
     */
    val Decelerate: Easing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)

    // ── 弹簧：数值取自 Material 3 Expressive 的 motion tokens（mass = 1）─────────────────────
    // 上游 component 里的用法是 `MotionScheme.expressive()` 的 default/fast spatial spec，
    // 但那套 API 在 material3 1.4，本项目的 BOM（2024.12.01）锁在 1.3.1 —— 所以抄数值，
    // 不为了三个弹簧去升整条工具链（Compose 1.8 + Kotlin 2.2 的连带升级）。
    // 源：ExpressiveMotionTokens.kt
    //   fast spatial 0.6 / 800 · default spatial 0.8 / 380 · slow spatial 0.8 / 200
    //   effects（透明度/颜色）一律 1.0 / 3800·1600·800（临界阻尼，不弹）
    // https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/
    // compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ExpressiveMotionTokens.kt

    /**
     * 按下（起）：expressive **fast** spatial。0.6 的阻尼比会留一点点回弹，
     * 按下去"弹"一下比匀速放大有生命感；800 的刚度让它在 ~120ms 内到位，不会觉得慢。
     */
    val PressDown: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /**
     * 松开（收）：expressive **default** spatial。比下去慢、比下去稳（0.8 几乎不弹），
     * 一快一慢的对比就是"按下轻快、回弹从容"。
     */
    val PressUp: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /**
     * 橡皮筋回位：**临界阻尼**（1.0 / 380）—— 一路收回 0，不过冲。
     *
     * 这里特意不弹：0.72 时弹簧会越过 0 再回来，在大位移（64dp）上就是"复位后又抽一下"
     * （2026-11-23 用户实测）。橡皮筋的"弹"应该来自拉动时的阻尼，不是回位时晃。
     */
    val RubberBand: SpringSpec<Float> = spring(dampingRatio = 1.0f, stiffness = 380f)
}
