package app.tavern

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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

    /** 跟手比例：0.5 表示手指拖 10dp，内容只走 5dp。越小越"硬"。 */
    var damping: Float = 0.5f

    /** 最大可拉出的距离（dp），再拉就不动了。 */
    var maxStretchDp: Float = 56f
}

@Composable
fun rememberRubberOverscroll(): RubberOverscrollState = remember { RubberOverscrollState() }

fun Modifier.rubberOverscroll(state: RubberOverscrollState): Modifier {
    val density = LocalDensity.current
    val maxPx = with(density) { state.maxStretchDp.dp.toPx() }

    val connection = remember(state, maxPx) {
        object : NestedScrollConnection {
            var settling = false

            /** 往里拖时先把橡皮筋收回去，再让列表正常滚。 */
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
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
                val current = state.offset
                val target = (current + available.y * state.damping).coerceIn(-maxPx, maxPx)
                val delta = target - current
                if (delta == 0f) return Offset.Zero
                state.offset = target
                // 报告"已消费"的原始量（除以阻尼还原），避免系统 overscroll 再叠一层
                return Offset(0f, delta / state.damping)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (state.offset == 0f || settling) return Velocity.Zero
                settling = true
                val start = state.offset
                animate(
                    initialValue = start,
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                ) { value, _ ->
                    state.offset = value
                }
                settling = false
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .graphicsLayer { translationY = state.offset }
}
