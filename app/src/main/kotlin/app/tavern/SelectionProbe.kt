package app.tavern

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import app.tavern.data.log.AppLog

/**
 * 选字诊断用的坐标探针（2026-11-25 临时，结案后删）。
 *
 * 为什么要它：真机上"长按气泡选不字"有三种完全不同的死法 ——
 * ① 按下没落到正文上（被别的东西截走、或视觉位置与布局位置不一致）；
 * ② 落到了正文上，但长按被判取消（手指出了正文框、或别的手势吃掉事件）；
 * ③ 长按成立，但选区没建起来。
 * 三者要改的地方完全不同，靠猜已经错了两轮，所以这里先把"按下打在哪个矩形上"记下来。
 *
 * 约定：
 * - **只读**：只是 await 事件，从不 consume —— 不会改变任何既有手势的行为；
 * - 用普通 class 存坐标而不是 state：`onGloballyPositioned` 每次滚动都会触发，
 *   写成 state 会让整页每帧重组。
 */
class ProbeBounds {
    var coords: LayoutCoordinates? = null
    var inWindow: Rect = Rect.Zero
}

/** 记下这个节点的窗口矩形（不触发重组）。 */
fun Modifier.trackProbe(ref: ProbeBounds): Modifier = this.onGloballyPositioned {
    ref.coords = it
    ref.inWindow = it.boundsInWindow()
}

/**
 * 每次按下记一行：按下点的窗口坐标、这个节点的窗口矩形、按下事件是否已被别人吃掉，
 * 以及 [extra] 里的现场信息；随后再记一行"长按成立 / 没成立"。
 */
fun Modifier.selectionProbe(
    tag: String,
    ref: ProbeBounds,
    extra: () -> String = { "" },
): Modifier = this.composed {
    val latestExtra = rememberUpdatedState(extra)
    Modifier.pointerInput(tag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val win = ref.coords?.let {
                runCatching { it.localToWindow(down.position) }.getOrNull()
            }
            val winText = if (win != null && win.isSpecified) {
                "(${win.x.toInt()},${win.y.toInt()})"
            } else {
                "-"
            }
            AppLog.i(
                "sel",
                "$tag 按下 窗口=$winText 节点窗口=" +
                    "(${ref.inWindow.left.toInt()},${ref.inWindow.top.toInt()})-(" +
                    "${ref.inWindow.right.toInt()},${ref.inWindow.bottom.toInt()}) " +
                    "已消耗=${down.isConsumed} ${latestExtra.value()}",
            )
            val startedAt = SystemClock.elapsedRealtime()
            val longPress = awaitLongPressOrCancellation(down.id)
            val cost = SystemClock.elapsedRealtime() - startedAt
            AppLog.i(
                "sel",
                if (longPress != null) {
                    "$tag 长按成立 ${cost}ms 位置=" +
                        "(${longPress.position.x.toInt()},${longPress.position.y.toInt()})"
                } else {
                    "$tag 长按没成立 ${cost}ms（提前抬起 / 被取消）"
                },
            )
        }
    }
}
