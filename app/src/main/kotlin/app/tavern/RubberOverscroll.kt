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
import app.tavern.data.log.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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
     * 代数：每次手指接管 +1。回弹动画在**写 offset 之前**核对代数，变了就不写。
     *
     * 为什么光 `cancel()` 不够：动画是每帧回调，`cancel()` 只是标记，
     * 已经排进这一帧的那次回调照样会执行 —— 它会把用户刚拖出来的位移**盖回动画的轨迹**，
     * 真机上就是"抽一下"（2026-11-23 日志里"回弹被打断 offset=0 / 回弹结束 offset=23"这一对就是它）。
     */
    internal var gen: Int = 0

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
                val running = state.settleJob?.isActive == true
                state.gen++
                state.settleJob?.cancel()
                state.settleJob = null
                if (running) AppLog.i("rubber", "回弹被打断 offset=${state.offset.roundToInt()}")
            }

            /** 回弹初速度的上限：跟当前拉伸量挂钩（px/s），防止一次大力甩把值甩飞。 */
            private fun startAbsLimit(absOffset: Float): Float = absOffset * 8f

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
                state.offset = target
                // **报告"全吃掉了"**，不要返回 `delta / damping`。
                //
                // 我们是滚动链的最外层，剩下的量没人再消费；报告不足（阻尼 < 1、room < 1 时必然不足）
                // 会让系统把它当成"还没消费"，于是 Android 12+ 的 overscroll 拉伸再叠一层 ——
                // 这就是"每个页面最大可拉距离不一样"的真凶：内容越短、越能拖动，平台拉伸叠得越多
                // （角色卡页内容少，所以显得最长）。现在这一层由我们独占，各页面手感一致。
                return Offset(0f, available.y)
            }

            /**
             * 开始回弹。
             *
             * 用独立 job：普通 scroll 回调里没法 cancel 一个 suspend 的 `animate()`，
             * 有了 job 句柄 + 代数，手指一落就能立刻夺权。
             * 不 join：回弹只是"视觉归位"，不该把滚动链按在那儿半秒。
             */
            private fun startSettle(initialVelocity: Float = 0f) {
                val start = state.offset
                if (start == 0f) return
                val sign = if (start >= 0f) 1f else -1f
                val gen = state.gen
                val startAbs = abs(start)
                // 松手速度要带进弹簧：**临界阻尼从静止出发时初速度是 0，头 100ms 几乎不动** ——
                // 用户看到的就是"拉伸后悬停一会儿才回去"（2026-11-23）。反向甩时速度是负的，
                // 于是回弹立刻起步，正好符合"往反方向拉应该马上收"的直觉。
                // 上限压一下，避免一次大力甩把值甩飞（写入端还有 coerce 兜底）。
                val v0 = initialVelocity.coerceIn(-startAbsLimit(startAbs), startAbsLimit(startAbs))
                // 诊断：正常回弹的 |offset| 只该单调变小；反涨说明有人（旧动画/外部回调）又把它推出去了
                var grew = false
                AppLog.i("rubber", "回弹 start=${start.roundToInt()}px v0=${v0.roundToInt()}")
                state.settleJob = scope.launch {
                    animate(
                        initialValue = start,
                        targetValue = 0f,
                        initialVelocity = v0,
                        animationSpec = Motion.RubberBand,
                    ) { value, _ ->
                        // ① 代数变了 = 手指已经接管，这一帧不许再写（否则把拖出来的位移盖回去）
                        // ② 只许朝 0 收敛，不许反向：过冲在列表上就是"复位后又抽一下"
                        if (state.gen == gen) {
                            if (abs(value) > startAbs + 1f) grew = true
                            state.offset = (value * sign).coerceAtLeast(0f) * sign
                        }
                    }
                    if (state.gen == gen) {
                        AppLog.i("rubber", "回弹结束 offset=${state.offset.roundToInt()}px 反涨=$grew")
                    }
                }
            }

            /**
             * 松手立即回弹：**吃掉全部速度**并立刻开始。
             *
             * 不能只在 `onPostFling` 里做（2026-11-23 用户："没有回弹动画了"）：
             * 上一版我们独占滚动量之后，列表自己的 fling 会把橡皮筋的位移抹平/搅乱，
             * `onPostFling` 到达时要么 offset 已经回到 0（直接 return，看着就是"不弹了"），
             * 要么被拖到很晚。这里在 fling **开始之前**就接管：速度全吃、马上回弹。
             */
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (state.offset == 0f) return Velocity.Zero
                takeOver()
                startSettle(available.y)
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // 兜底：万一 onPreFling 没走到（比如速度本来就被吃光了），这里再补一次
                if (state.offset != 0f && state.settleJob?.isActive != true) {
                    takeOver()
                    startSettle(available.y)
                }
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .graphicsLayer { translationY = state.offset }
}
