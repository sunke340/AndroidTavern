package app.tavern

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * 动效 tokens。
 *
 * 三条规矩：
 * 1. 只做**帮助理解状态变化**的动画（内容换了、消息来了、按钮变成了另一个动作），不做装饰；
 * 2. 时长压在 120–240ms：手机上超过 300ms 会显得"卡"；
 * 3. 缓冲曲线统一，不要各处各写一个。
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
}
