package app.tavern

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** 玻璃高斯半径。有 RenderEffect 时把放大后的磨砂再磨细一遍；没有也不影响下限。 */
internal val GlassBlur = 12.dp

/** 降采样倍数：内容层按 1/[FrostScale] 录一份小层，放大时的线性插值就是"糊"。 */
private const val FrostScale = 8f

/** 玻璃白底浓度：越高越像奶白塑料，越低越透。手感调这里。 */
private const val GlassFillAlpha = 0.34f

/**
 * 把刚录好的内容层 [from] 按 1/[FrostScale] 再录一份小层 —— 这份就是磨砂素材。
 * 必须在内容层 `record` 之后、同一个 DrawScope 里调用。
 */
internal fun DrawScope.recordFrost(frost: GraphicsLayer, from: GraphicsLayer, size: IntSize) {
    frost.record(
        size = IntSize(
            (size.width / FrostScale).toInt().coerceAtLeast(1),
            (size.height / FrostScale).toInt().coerceAtLeast(1),
        ),
    ) {
        scale(1f / FrostScale, 1f / FrostScale, pivot = Offset.Zero) {
            drawLayer(from)
        }
    }
}

/**
 * 让节点比父给它的尺寸每边多出 [pad]，并往左上挪同样的距离。
 *
 * 模糊要采样到面**外面**的内容，否则离屏缓冲只有这块面那么大，边缘会被 clamp 成一条条直纹。
 * 多出来的部分由外层的 `clip(shape)` 裁掉。
 */
private fun Modifier.expandForBlur(pad: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints.fixed(constraints.maxWidth + pad * 2, constraints.maxHeight + pad * 2),
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-pad, -pad) }
}

/**
 * 玻璃面：磨砂底 + 半透明面 + 1px 描边，内容放最后。
 *
 * 磨砂来自 [frost]（内容层按 1/[FrostScale] 录的小层）：按"本节点位置 - 内容位置"把它放大画回来。
 * 整条链路只用 `record` / `drawLayer` / `scale`，不依赖 `RenderEffect`；
 * `Modifier.blur` 只是加分项，设备不支持时观感下限仍在。
 *
 * **调用方负责位置**：本节点要在窗口坐标里能量到（内部自己 `onGloballyPositioned`），
 * 并且 [contentOrigin] 必须是"录那份内容时的那个节点"的窗口位置，两者坐标系必须一致。
 *
 * 玻璃档不挂阴影：elevation 阴影画在内容后面，配半透明底会透出亮度台阶（踩过）。
 */
@Composable
fun GlassSurface(
    frost: GraphicsLayer,
    contentOrigin: () -> Offset,
    glass: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalTavernPalette.current
    val surface = MaterialTheme.colorScheme.surface
    val pad = with(LocalDensity.current) { GlassBlur.roundToPx() }
    var nodeOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .clip(shape)
            .onGloballyPositioned { nodeOrigin = it.positionInWindow() },
    ) {
        if (glass) {
            Box(Modifier.matchParentSize().expandForBlur(pad)) {
                Canvas(Modifier.fillMaxSize().blur(GlassBlur)) {
                    val origin = nodeOrigin - contentOrigin()
                    // 这个节点比玻璃面大一圈，所以先把 pad 加回来；小层是 1/FrostScale 的，换算过去。
                    translate(left = pad.toFloat(), top = pad.toFloat()) {
                        scale(FrostScale, FrostScale, pivot = Offset.Zero) {
                            translate(left = -origin.x / FrostScale, top = -origin.y / FrostScale) {
                                drawLayer(frost)
                            }
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .matchParentSize()
                .background(if (glass) surface.copy(alpha = GlassFillAlpha) else surface)
                .border(1.dp, palette.cardBorder, shape),
        )

        content()
    }
}
