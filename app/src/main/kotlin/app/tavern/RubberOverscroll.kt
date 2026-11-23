package app.tavern

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 列表拉到两端后的**橡皮筋**：继续拖会带阻尼地跟手，松手弹回去。
 *
 * 系统的 overscroll（Android 12+ 是拉伸）在列表里只做"视觉拉伸"，位移几乎看不出来；
 * 这里用 `NestedScrollConnection` 自己接住"列表消费不掉的那部分滚动量"，
 * 换算成内容位移（[damping] 阻尼），松手用弹簧回 0。
 *
 * 用法：`Modifier.rubberOverscroll(state)`，注意要挂在滚动容器上、且在 `verticalScroll` /
 * `LazyColumn` 之前（外层），这样它才在"内层滚不动"时拿到剩余的 `available`。
 */
class RubberOverscrollState internal constructor() {
    internal var offset by mutableFloatStateOf(0f)

    /** 正在跑的回弹动画。手指一落就要掐掉它，否则新手势会被旧动画拽回去。 */
    internal var settleJob: Job? = null

    /**
     * 跟手比例：0.62 表示手指拖 10dp，内容走 6.2dp。
     * 越小越"硬"（拉不动），越大越飘。只在**起步**是这个比例，越拉阻力越大（见 `onPostScroll` 里的 room 系数）。
     */
    var damping: Float = 0.62f

    /** 最大可拉出的距离（dp），到这儿就拉不动了。 */
    var maxStretchDp: Float = 64f
}

@Composable
fun rememberRubberOverscroll(): RubberOverscrollState = remember { RubberOverscrollState() }

@Composable
fun Modifier.rubberOverscroll(state: RubberOverscrollState): Modifier {
    val density = LocalDensity.current
    val maxPx = with(density) { state.maxStretchDp.dp.toPx() }
    val scope = rememberCoroutineScope()

    val connection = remember(state, maxPx, scope) {
        object : NestedScrollConnection {

            /**
             * 手指一上来就先夺权：把还在跑的回弹动画停掉。
             *
             * 不这么做的话，回弹期间用户再拉，`onPostScroll` 写进 [RubberOverscrollState.offset] 的值
             * 下一帧就被旧动画覆盖 —— 表现是"不跟手，还在播上一次的橡皮筋"（2026-11-23 用户实测）。
             * 停掉之后新手势直接从**当前位移**继续，阻尼照常算，视觉上就是"抓住了"。
             *
             * 不判断 `source`：回弹自己的动画不走 scroll 回调，不会自打断；而 Fling 阶段也不经过这里。
             */
            private fun takeOver() {
                state.settleJob?.cancel()
                state.settleJob = null
            }

            /** 往里拖时先把橡皮筋收回去，再让列表正常滚。 */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                takeOver()
                val current = state.offset
                if (current == 0f || available.y == 0f) return Offset.Zero
                // 向下拉出后往上推（或反过来）：优先抵消橡皮筋
                if (current > 0f && available.y < 0f) {
                    val consumed = maxOf(-current, available.y)
                    state.offset = current + consumed
                    return Offset(0f, consumed)
                }
                if (current < 0f && available.y > 0f) {
                    val consumed = minOf(-current, available.y)
                    state.offset = current + consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            /** 列表已经滚不动了，剩下的滚动量变成内容位移。 */
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y == 0f) return Offset.Zero
                takeOver()
                val current = state.offset
                // 渐进阻尼：起步按 damping 跟手，越接近 max 阻力越大（到顶时导数为 0，不再是硬墙）。
                // 线性阻尼（老写法）是"全程一样重、撞上 max 突然停"，手感发硬、发死。
                val room = (1f - abs(current) / maxPx).coerceIn(0f, 1f)
                val target = (current + available.y * state.damping * room).coerceIn(-maxPx, maxPx)
                val delta = target - current
                if (delta == 0f) return Offset.Zero
                state.offset = target
                // 报告"已消费"的原始量（除以阻尼还原），避免系统 overscroll 再叠一层
                return Offset(0f, delta / state.damping)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (state.offset == 0f) return Velocity.Zero
                takeOver()
                // 用独立 job 跑回弹：`onPostFling` 自己也 await 它，保持 fling 链的顺序；
                // 但用户中途抓列表时 takeOver() 能把它掐掉（suspend 的 animate 没法从普通回调里取消）。
                val job = scope.launch {
                    animate(
                        initialValue = state.offset,
                        targetValue = 0f,
                        animationSpec = Motion.RubberBand,
                    ) { value, _ -> state.offset = value }
                }
                state.settleJob = job
                job.join()
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .graphicsLayer { translationY = state.offset }
}
