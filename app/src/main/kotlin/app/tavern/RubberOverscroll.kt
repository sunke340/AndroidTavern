package app.tavern

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
 * 列表拉到两端后的**橡皮筋**：继续拖带阻尼跟手，松手弹回去。
 *
 * 算法照 Jetpack Compose 官方 [androidx.compose.foundation.OverscrollEffect] 的做法
 * （`applyToScroll` 接边界剩余位移、`applyToFling` 接剩余速度、弹簧回 0），
 * 我们这版用 `NestedScrollConnection` 实现事件部分，视觉位移单独算：
 *
 * 1. **累计"原始越界量" `raw`**（手指拖出去多少就算多少，不做逐帧阻尼）；
 * 2. 视觉位移 `visual` = 橡皮筋公式 `raw·R·K / (R + K·raw)` —— 越拉越紧、**没有硬墙**；
 * 3. 松手让 `raw` 用弹簧回 0，`visual` 由同一个公式自然跟随。
 *
 * 为什么不再"逐帧乘阻尼 + coerceIn 上限"（旧写法，2026-11-23 换掉）：
 * ① 撞上限就是硬墙，拉到头再松手会"停在最远处"；② 逐帧阻尼和回弹动画互相覆盖，
 * 才有"拉伸后悬停"。现在 raw 可以无限涨、视觉只是渐近，物理上不会再撞墙。
 */
class RubberOverscrollState internal constructor() {
    /** 原始越界量（px）：手指拖出去多少。可正可负。 */
    internal var raw by mutableFloatStateOf(0f)

    /** 当前视觉位移（px），= 橡皮筋公式([raw])。给 [rubberOverscroll] 的 graphicsLayer 用。 */
    internal var visual by mutableFloatStateOf(0f)

    /** 公式里的"参考尺寸"（px）：固定值，**不跟着页面高度变**，这样每页手感一致。 */
    internal var referencePx by mutableFloatStateOf(0f)

    /**
     * [raw] 的上限（px），由"允许的最大视觉位移"反解出来（默认 64dp）。
     *
     * **必须有**：一次快速甩动撞到顶部时，fling 的剩余惯性会整段灌进 [raw] ——
     * 2026-11-23 日志里出现过 `raw=-10658px visual=-1149`（3 米手指行程、328dp 位移），
     * 回弹要倒着走完这 10k 像素，于是内容在**最远处停住几百毫秒**，就是用户说的"悬停"。
     */
    internal var rawLimit: Float = Float.MAX_VALUE


    /**
     * 是否是 **fling** 送来的位移（而不是手指拖动）。
     *
     * 这个区分很关键：一次快速甩动撞到顶之后，fling 还会继续送几百毫秒的残余位移；
     * 这段时间只能"吃掉"，不能再累积 [raw]、也不能打断已经开始回弹的动画，
     * 否则内容会被反复按在远端（用户看到的就是"悬停"）。
     */
    internal fun isFling(source: NestedScrollSource): Boolean =
        source == NestedScrollSource.Fling

    /**
     * 这次 fling 的惯性**已经用完**（它把橡皮筋顶到过上限）：此后再送来的位移一律只吃掉。
     *
     * **必须有这个标记，光"在上限那一刻挡一下"不够**（2026-11-23 晚第二次修）：
     * 回弹一启动，下一帧 raw 就离开上限（弹簧 16ms 走掉 7%），上一版的守卫立刻失效 →
     * 落进 `takeOver()` 把刚启动的回弹 cancel 掉 → 再灌满 raw → 再 startSettle……
     * 逐帧循环，整个 fling 期间内容都被按在最远处，用户看到的就是"悬停"。
     *
     * 清标记的地方只有一个：**新的 Drag 位移**（真正的下一根手指）。
     */
    internal var flingEaten: Boolean = false

    /** 正在跑的回弹动画。手指一落就掐掉它，否则新手势会被旧动画拽回去。 */
    internal var settleJob: Job? = null

    /**
     * 代数：每次手指接管 +1。回弹动画写 [raw] 之前核对代数，变了就不写。
     *
     * 为什么光 `cancel()` 不够：动画是每帧回调，`cancel()` 只是标记，
     * 已经排进这一帧的那次回调照样会执行 —— 它会把用户刚拖出来的位移盖回动画轨迹。
     */
    internal var gen: Int = 0

    /** 橡皮筋常数：越小越"硬"。0.6 时约 120dp 手指 → 64dp 位移。 */
    var rubberConstant: Float = 0.6f

    /**
     * 系统「移除动画」开着时：跟手照旧（那是手指直接操纵），但松手**直接归零**，不播回弹弹簧。
     * 见 [rememberReduceMotion]。
     */
    internal var reduceMotion: Boolean = false

    /** 把 [raw] 映射成视觉位移（橡皮筋公式）。 */
    internal fun applyRaw(next: Float) {
        raw = next
        val r = abs(next)
        val ref = if (referencePx > 0f) referencePx else 1f
        val k = rubberConstant
        val eased = (r * ref * k) / (ref + k * r)
        visual = if (next < 0f) -eased else eased
    }
}

@Composable
fun rememberRubberOverscroll(): RubberOverscrollState = remember { RubberOverscrollState() }

@Composable
fun Modifier.rubberOverscroll(
    state: RubberOverscrollState,
    reduceMotion: Boolean = rememberReduceMotion(),
): Modifier {
    val density = LocalDensity.current
    state.reduceMotion = reduceMotion
    // 参考尺寸固定 400dp：不随页面高度变，保证"每页拉到头距离一样"
    val referencePx = with(density) { 400.dp.toPx() }
    state.referencePx = referencePx
    // 由"允许的最大视觉位移"反解 raw 上限：visual = r·R·K/(R+K·r)  →  r = V·R/(K·(R−V))
    val maxVisualPx = with(density) { 64.dp.toPx() }
    state.rawLimit = maxVisualPx * referencePx /
        (state.rubberConstant * (referencePx - maxVisualPx)).coerceAtLeast(1f)
    val scope = rememberCoroutineScope()

    val connection = remember(state, referencePx, scope) {
        object : NestedScrollConnection {

            /** 手指一上来就先夺权：把还在跑的回弹动画停掉。 */
            private fun takeOver() {
                val running = state.settleJob?.isActive == true
                state.gen++
                state.settleJob?.cancel()
                state.settleJob = null
                if (running) AppLog.i("rubber", "回弹被打断 raw=${state.raw.roundToInt()}")
            }

            /** 开始回弹：把 raw 弹回 0（视觉位移按公式跟着走）。 */
            private fun startSettle(initialVelocity: Float = 0f) {
                if (state.raw == 0f) return
                // 系统「移除动画」：不做回弹动画，位移立刻归零（跟手阶段不受影响）
                if (state.reduceMotion) {
                    state.applyRaw(0f)
                    return
                }
                val gen = state.gen
                val start = state.raw
                AppLog.i(
                    "rubber",
                    "回弹 start raw=${start.roundToInt()} px visual=${state.visual.roundToInt()}",
                )
                state.settleJob = scope.launch {
                    animate(
                        initialValue = start,
                        targetValue = 0f,
                        // 只接"往回收"的速度：朝外的速度会让它松手后继续往外撑，
                        // 远端停住再回来 —— 那就是"拉伸后悬停"（2026-11-23）。
                        initialVelocity = if (start * initialVelocity < 0f) initialVelocity else 0f,
                        // 临界阻尼 + 较硬的弹簧：从静止起步也不会拖（旧值 380 时尾巴长）。
                        animationSpec = spring(dampingRatio = 1.0f, stiffness = 700f),
                    ) { value, _ ->
                        if (state.gen == gen) state.applyRaw(value)
                    }
                    if (state.gen == gen) {
                        AppLog.i("rubber", "回弹结束 visual=${state.visual.roundToInt()}")
                    }
                }
            }

            /** 往回拖时先把橡皮筋收回去，再让列表正常滚。 */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // fling 不接管：它送来的位移由 onPostScroll 吃掉，绝不能让动画被反复掐死
                if (state.isFling(source)) return Offset.Zero
                takeOver()
                state.flingEaten = false
                val current = state.raw
                if (current == 0f || available.y == 0f) return Offset.Zero
                if (current > 0f && available.y < 0f) {
                    val consumed = maxOf(-current, available.y)
                    state.applyRaw(current + consumed)
                    return Offset(0f, consumed)
                }
                if (current < 0f && available.y > 0f) {
                    val consumed = minOf(-current, available.y)
                    state.applyRaw(current + consumed)
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            /** 列表滚不动了：剩下的滚动量累积成原始越界量（视觉由公式决定，不做逐帧阻尼）。 */
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y == 0f) return Offset.Zero
                val fromFling = state.isFling(source)
                val limit = state.rawLimit
                if (fromFling) {
                    // 惯性已经用完这一次 fling：剩下的位移只吃掉。
                    // 不累积（否则 raw 被灌到上万像素）、不 takeOver（否则回弹被反复重置）、
                    // 也不碰 rawLimit 的判断 —— 判据是"这次 fling"，不是"raw 现在恰好在上限"。
                    if (state.flingEaten) return Offset(0f, available.y)
                    if (abs(state.raw) >= limit - 0.5f) {
                        takeOver()   // 先把可能还在跑的回弹掐掉，再从这里重新起一条
                        state.flingEaten = true
                        AppLog.i("rubber", "fling 撞上限，残余惯性丢弃 raw=${state.raw.roundToInt()}")
                        startSettle()
                        return Offset(0f, available.y)
                    }
                } else {
                    state.flingEaten = false
                }
                takeOver()
                val next = state.raw + available.y
                if (abs(next) >= limit) {
                    state.applyRaw(if (next < 0f) -limit else limit)
                    // **fling 一撞到上限就立刻回弹**，不等它自己跑完 —— 等 fling 结束（几百毫秒）
                    // 才回弹，内容就在远端干等着，那就是"悬停"。
                    if (fromFling) {
                        state.flingEaten = true
                        AppLog.i("rubber", "fling 撞上限，残余惯性丢弃 raw=${state.raw.roundToInt()}")
                        startSettle()
                    }
                } else {
                    state.applyRaw(next)
                }
                // 报告"全部吃掉"：这一层由我们独占，系统的 overscroll 拉伸不再叠一层
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (state.raw == 0f) return Velocity.Zero
                takeOver()
                startSettle(available.y)
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (state.raw != 0f && state.settleJob?.isActive != true) {
                    takeOver()
                    startSettle(available.y)
                }
                return Velocity.Zero
            }
        }
    }

    // ⚠️ `clipToBounds()` 必须在 graphicsLayer **外层**（写在前面）：橡皮筋的位移是绘制量，
    // 不裁的话内容会被画到自己槽位外面去 —— 典型症状是"往上拖到头，内容盖住上面的 Tab 栏"
    // （2026-11-25 用户在角色卡详情页报的：日志里同一时间窗 `rubber 回弹 start raw=-444
    // visual=-224`，即内容被画高了 81dp，而 Tab 栏只有 48dp 高）。
    // 裁在自己槽位上不会削弱橡皮筋观感：拉出来的仍是"槽位内露出空隙"，只是不再溢出到邻居身上。
    return this
        .clipToBounds()
        .nestedScroll(connection)
        .graphicsLayer { translationY = state.visual }
}
