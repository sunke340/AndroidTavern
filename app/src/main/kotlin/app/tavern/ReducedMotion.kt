package app.tavern

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 系统「移除动画」是否开着（设置 → 无障碍 → 移除动画；开发者选项里把 Animator 时长缩放调成"关闭"也算）。
 *
 * **为什么要有这个**：安卓用户可以一键把系统动画关掉（前庭失调、晕动症人群常用），
 * 系统自己的转场会跟着变，但 App 里手写的动画不会 —— 除非我们主动查。
 * 判据取自 [ValueAnimator.areAnimatorsEnabled]（minSdk 26 起可用），
 * 在 ON_RESUME 时重读一次：用户去设置里开关再回来，不用重启 App 就生效。
 *
 * **减动效不等于零动效**（Apple/Emil 的一致口径，见 docs/design/motion.md）：
 * 保留「淡入淡出」和「手指直接拖拽」这种帮助理解的变化，去掉**位移**与**缩放**。
 * 所以各处的用法是：
 * - 翻页 / 切 tab：滑动 → 纯淡入淡出；
 * - 按压反馈：放大 → 不缩放；
 * - 橡皮筋：跟手保留，松手直接归零（不要弹簧回弹）。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val lifecycle = LocalLifecycleOwner.current
    var reduce by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reduce = !ValueAnimator.areAnimatorsEnabled()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    return reduce
}
