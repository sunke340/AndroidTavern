package app.tavern

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** 描边宽度：液体玻璃的边缘是一圈"光"，1dp 就够，粗了像塑料壳。 */
private val RimWidth = 1.2.dp

/** 投影：模糊的黑胶囊垫在下面，不用 elevation（elevation 阴影会被半透明底透成亮度台阶）。 */
private val ShadowBlur = 18.dp
private val ShadowOffsetY = 4.dp
private const val ShadowAlpha = 0.10f

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
 * 玻璃面：软投影 + 磨砂底 + 半透明面 + 渐变描边，内容放最后。
 *
 * 磨砂来自 [frost]（内容层按 1/[FrostScale] 录的小层）：按"本节点位置 - 内容位置"把它放大画回来。
 * 整条链路只用 `record` / `drawLayer` / `scale`，不依赖 `RenderEffect`；
 * `Modifier.blur` 只是加分项，设备不支持时观感下限仍在。
 *
 * **调用方负责位置**：本节点要在窗口坐标里能量到（内部自己 `onGloballyPositioned`），
 * 并且 [contentOrigin] 必须是"录那份内容时的那个节点"的窗口位置，两者坐标系必须一致。
 *
 * 边缘用**渐变描边**而不是纯色：上边比背景更亮（受光），下边偏暗，两端渐变成主题色 ——
 * 一圈均匀的 1px 灰线读起来像塑料壳，没有厚度感。
 *
 * 投影**自己画**（模糊的黑胶囊往下压几像素），不用 `Modifier.shadow`：
 * elevation 阴影画在内容后面，配半透明底会在内部留下亮度台阶（2026-11-22 实测 4 级）。
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
    val accent = MaterialTheme.colorScheme.primary
    val pad = with(LocalDensity.current) { GlassBlur.roundToPx() }
    var nodeOrigin by remember { mutableStateOf(Offset.Zero) }

    // 外壳**不裁**：投影与模糊描边都要画到形状外面。
    Box(modifier = modifier.onGloballyPositioned { nodeOrigin = it.positionInWindow() }) {
        if (glass) {
            // 投影：一层模糊的黑色胶囊，往下压几像素。模糊自带渐变，内部不会出亮度台阶。
            // 必须 Unbounded：默认的 Rectangle 会把模糊结果裁到节点边界，光晕就没了。
            Box(
                Modifier
                    .matchParentSize()
                    .offset(y = ShadowOffsetY)
                    .blur(ShadowBlur, BlurredEdgeTreatment.Unbounded)
                    .drawBehind {
                        drawRoundRect(
                            color = Color.Black.copy(alpha = ShadowAlpha),
                            cornerRadius = CornerRadius(size.height / 2f),
                        )
                    },
            )
        }

        // 本体：先裁形状，磨砂与底色都画在里面
        Box(Modifier.matchParentSize().clip(shape)) {
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
                    .background(if (glass) surface.copy(alpha = GlassFillAlpha) else surface),
            )
        }

        // 边缘：玻璃档是"上亮下暗 + 两端压主题色"的渐变描边（一圈纯色读起来像塑料壳），
        // 轻量档维持 1px 实色。描边画在不裁的层上，否则外侧半个像素会被切掉。
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val stroke = RimWidth.toPx()
                    val radius = CornerRadius(size.height / 2f)
                    if (glass) {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(
                                    Color.White,
                                    Color.White.copy(alpha = 0.35f),
                                    palette.cardBorder,
                                    accent.copy(alpha = 0.45f),
                                ),
                            ),
                            cornerRadius = radius,
                            style = Stroke(stroke),
                        )
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    accent.copy(alpha = 0.40f),
                                    Color.Transparent,
                                    accent.copy(alpha = 0.40f),
                                ),
                            ),
                            cornerRadius = radius,
                            style = Stroke(stroke),
                        )
                    } else {
                        drawRoundRect(
                            color = palette.cardBorder,
                            cornerRadius = radius,
                            style = Stroke(stroke),
                        )
                    }
                },
        )

        content()
    }
}
