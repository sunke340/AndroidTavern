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

    /** 位移较大的过渡：列表重排 */
    const val Slow = 240

    /** 标准曲线：快出慢入，Material 的 emphasized 近似 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 纯减速：用于"新内容出现" */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

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
