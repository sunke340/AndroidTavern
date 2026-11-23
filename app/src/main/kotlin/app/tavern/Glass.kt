package app.tavern

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
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
 * 边缘高光是**一圈宽带**，不是一条线 —— 参考系统相机那种：上下一整片白，宽 3~5dp。
 * 由外向内叠几层（宽度, 透明度），叠加后就是"边缘最亮、往内渐淡"的厚度感。
 */
private val RimLayers = listOf(
    // 透明度别太高：高光是白色叠加，太白会把下面的磨砂层盖死，看起来像实心白圈。
    // 降低后边缘仍亮，但能透出被糊过的背景（用户 2026-11-23：高光需要有磨砂感）。
    1.0.dp to 0.55f,
    2.5.dp to 0.32f,
    5.0.dp to 0.18f,
    8.0.dp to 0.09f,
)

/** 按下放大：整块玻璃（投影、高光、内容）一起放，不做"只有图标动"也不压暗。 */
private const val PressScale = 1.25f

/** 按压动画时长：90ms 太快（看不出过程），170ms 才有"弹一下"的手感。 */
private const val PressDurationMs = 170



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

/** 玻璃浮层要的两样东西：磨砂素材 + 内容原点。浮层（页头/底栏/输入区）用它反过来采样"背后的内容"。 */
class GlassBackdrop(
    val frost: GraphicsLayer,
    val contentOrigin: () -> Offset,
)

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
    /**
     * 磨砂素材（内容小层）。传 null 表示**不采背景**，只画半透面 + 高光边 + 投影 ——
     * 给"自己也在录制范围里"的元素用（比如聊天输入框）：采样自己的图层会变成自反馈。
     */
    frost: GraphicsLayer?,
    contentOrigin: () -> Offset = { Offset.Zero },
    glass: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
    /**
     * 按压反馈的来源。传了它，整块玻璃会一起缩一点、并整体压暗；
     * 不传就没有按压反馈（列表底栏这种多按钮表面）。
     */
    interactionSource: MutableInteractionSource? = null,
    /** 覆盖玻璃面的填充色（发送钮用主题色）。null = 原来的半透明面。 */
    fillColor: Color? = null,
    /** 高光环的颜色；null = 白。发送钮这种主题色按钮要传主色，否则白边很扎眼。 */
    rimColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalTavernPalette.current
    val surface = MaterialTheme.colorScheme.surface
    val pad = with(LocalDensity.current) { GlassBlur.roundToPx() }
    var nodeOrigin by remember { mutableStateOf(Offset.Zero) }

    val pressed = if (interactionSource != null) {
        interactionSource.collectIsPressedAsState().value
    } else {
        false
    }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PressScale else 1f,
        // 非线性：先快后慢（Decelerate），配 170ms 才有弹性感
        animationSpec = tween(PressDurationMs, easing = Motion.Decelerate),
        label = "glass-press-scale",
    )

    // 外壳**不裁**：投影与模糊描边都要画到形状外面。
    // 缩放在外壳上：按下时投影、高光、内容一起缩，不会出现"只有图标变小"的错位。
    Box(
        modifier = modifier
            .onGloballyPositioned { nodeOrigin = it.positionInWindow() }
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
    ) {
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
            if (glass && frost != null) {
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
            // 主题色叠加层（发送钮）：叠在玻璃面上而不是替换它，这样淡紫仍然是"磨砂上的一层色"
            if (fillColor != null) {
                Box(Modifier.matchParentSize().background(fillColor))
            }
        }

        // 顶面薄光：上亮下透，玻璃才有"面"而不是一块色板
        if (glass) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(shape)
                    .drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color.White.copy(alpha = 0.22f),
                                0.55f to Color.Transparent,
                            ),
                        )
                    },
            )
        }

        // 边缘：对称的宽白环（多层叠加）＋ 两侧主题色。一圈 1dp 的线读起来只是"描边"。
        // 画在不裁的层上：外半个环压在页面底色上，正好当"光溢出"。
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val radius = CornerRadius(size.height / 2f)
                    if (glass) {
                        val rimTint = rimColor ?: Color.White
                        RimLayers.forEach { (width, alpha) ->
                            drawRoundRect(
                                color = rimTint.copy(alpha = alpha),
                                cornerRadius = radius,
                                style = Stroke(width.toPx()),
                            )
                        }
                    } else {
                        drawRoundRect(
                            color = palette.cardBorder,
                            cornerRadius = radius,
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                },
        )

        content()
    }
}

/**
 * 磨砂玻璃**带**：页头用。整条宽度画一块"糊掉的内容"，再用线性透明度遮罩往下收。
 *
 * 为什么页头要磨砂而不是纯色渐变：纯色遮罩要够实才挡得住滚过去的内容，
 * 一实就变成"一条不透明的色块"；磨砂把内容**糊掉**，标题在糊底上照样清楚，
 * 同时保留"内容从玻璃下面过"的观感（用户 2026-11-23 定的方向）。
 *
 * 遮罩是**一条**线性渐变（顶实底透），不做平台段 —— 与页头原先的要求一致。
 */
@Composable
fun GlassBand(
    frost: GraphicsLayer,
    contentOrigin: () -> Offset,
    /** 压在上面的底色（用页面背景色），给模糊层一点对比度。 */
    background: Color,
    modifier: Modifier = Modifier,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var bandSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier.onGloballyPositioned {
            origin = it.positionInWindow()
            bandSize = it.size
        },
    ) {
        // 磨砂层：按条切开，每条一个透明度（上实下透），整块再统一模糊一次。
        // 为什么不用 DstIn 遮罩：在真机上它把整层都擦掉了（页头变透明、文字全透出来），
        // 换成"分条透明度 + 父节点模糊"，只用最普通的绘制路径。
        Box(Modifier.matchParentSize().blur(GlassBlur)) {
            Column(Modifier.fillMaxSize()) {
                val stripHeight = if (bandSize.height > 0) bandSize.height / BandStrips.toFloat() else 0f
                repeat(BandStrips) { index ->
                    val stripAlpha = 1f - index / (BandStrips - 1f)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clipToBounds()
                            .graphicsLayer { alpha = stripAlpha },
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val offset = origin + Offset(0f, index * stripHeight) - contentOrigin()
                            scale(FrostScale, FrostScale, pivot = Offset.Zero) {
                                translate(left = -offset.x / FrostScale, top = -offset.y / FrostScale) {
                                    drawLayer(frost)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 底色：一条线性渐变（顶实底透），不加平台段
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to background.copy(alpha = BandTintAlpha),
                        1.00f to Color.Transparent,
                    ),
                ),
        )
    }
}

/** 磨砂带切几条；8 条配 12dp 模糊，肉眼看不出台阶。 */
private const val BandStrips = 8

/** 页头磨砂带的底色浓度：太低标题会和糊底糊在一起，太高又变回一条色块。 */
private const val BandTintAlpha = 0.62f

/** 浮层用：直接吃一个 [GlassBackdrop]。 */
@Composable
fun GlassSurface(
    backdrop: GlassBackdrop,
    glass: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    fillColor: Color? = null,
    rimColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) = GlassSurface(
    frost = backdrop.frost,
    contentOrigin = backdrop.contentOrigin,
    glass = glass,
    shape = shape,
    modifier = modifier,
    interactionSource = interactionSource,
    fillColor = fillColor,
    rimColor = rimColor,
    content = content,
)
