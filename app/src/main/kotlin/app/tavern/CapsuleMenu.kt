package app.tavern

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.tavern.data.log.AppLog
import kotlin.math.roundToInt

/**
 * 胶囊点开的**液态玻璃菜单**（需求批 E 的原型）。
 *
 * 为什么不用 `DropdownMenu`：它是**独立窗口**，采不到页面的内容层 —— 画出来只能是纯色板，
 * 做不了真玻璃。所以自研一个宿主：页面只 `menu.show(anchor, items)`，由骨架（`TabsRoot` /
 * `GlassDetailScaffold`）在**录图层之外**画出来（和动作单、确认框同一档），才采得到磨砂底片。
 *
 * 交互：点空白关掉；选一项先关再执行（和系统菜单一致）。
 * 位置：贴着锚点（那个胶囊）的**右下角**往左下长 —— 右上角的菜单本来就从那里长出来。
 */
class CapsuleMenuController {
    internal var spec: CapsuleMenuSpec? by mutableStateOf(null)
        private set

    /**
     * 有没有宿主。没接上时 `show()` 什么都不画 —— 这是本模块最容易静默失败的地方
     * （页面在骨架**之前**读 CompositionLocal 就会拿到这种孤儿实例），所以留一行日志。
     */
    internal var attached = false

    fun show(anchor: Rect, items: List<CapsuleMenuItem>) {
        if (!attached) AppLog.w("ui", "胶囊菜单请求没有宿主")
        spec = CapsuleMenuSpec(anchor, items)
    }

    fun dismiss() {
        spec = null
    }
}

/** 菜单里的一项。 */
class CapsuleMenuItem(val label: String, val onClick: () -> Unit)

internal data class CapsuleMenuSpec(val anchor: Rect, val items: List<CapsuleMenuItem>)

/** 页面骨架提供给内容区的控制器；没有宿主时 `show()` 只是不会画出来。 */
val LocalCapsuleMenu = staticCompositionLocalOf { CapsuleMenuController() }

/**
 * 菜单宿主。**只能在骨架的浮层里调用**（录图层之外），否则玻璃会采到自己。
 *
 * @param frost 该页录的磨砂底片（和动作单同一个）
 * @param contentOrigin 内容层的窗口坐标原点（玻璃按窗口坐标采样）
 */
@Composable
fun GlassCapsuleMenuHost(
    controller: CapsuleMenuController,
    frost: GraphicsLayer?,
    contentOrigin: () -> Offset,
    glass: Boolean,
) {
    val spec = controller.spec ?: return
    val density = LocalDensity.current
    val width = 208.dp
    val widthPx = with(density) { width.toPx() }
    val gapPx = with(density) { 6.dp.toPx() }

    // 入场：从锚点那一角"长出来"（缩放 0.92 → 1，透明度 0 → 1）。离开不做动画：
    // 点空白那一帧要立刻消失，等 150ms 才关会让人以为没点上。
    val enter = remember(spec) { Animatable(0f) }
    LaunchedEffect(spec) { enter.animateTo(1f, tween(Motion.Fast, easing = Motion.Decelerate)) }

    Box(Modifier.fillMaxSize()) {
        // 遮罩：只负责"点空白关掉"，不压暗（一片灰罩会把玻璃的观感全毁掉）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controller.dismiss() },
        )
        val x = (spec.anchor.right - widthPx).roundToInt()
        val y = (spec.anchor.bottom - contentOrigin().y + gapPx).roundToInt()
        Box(
            modifier = Modifier
                .offsetInWindow(x, y)
                .width(width)
                .graphicsLayer {
                    alpha = enter.value
                    scaleX = 0.92f + 0.08f * enter.value
                    scaleY = 0.92f + 0.08f * enter.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                },
        ) {
            GlassSurface(
                frost = frost,
                contentOrigin = contentOrigin,
                glass = glass,
                shape = RoundedCornerShape(Radius.card),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    spec.items.forEach { item ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    controller.dismiss()
                                    item.onClick()
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `Modifier.offset { }` 的薄封装：这里是窗口坐标（相对骨架内容层），所以要扣掉内容原点。 */
private fun Modifier.offsetInWindow(x: Int, y: Int): Modifier =
    this.then(Modifier.offset { IntOffset(x, y) })
