package app.tavern

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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

/**
 * 一次"按下去"至少占用的时长（ms）。
 *
 * 快速点按时 Press 和 Release 可能只隔十几毫秒，弹簧还没来得及长到能看见就收回来了 ——
 * 用户看到的是"点了但没反应"。给一个下限，反馈才稳定出现（2026-11-23 用户反馈）。
 */
private const val MinPressVisibleMs = 90L



/** 投影：模糊的黑胶囊垫在下面，不用 elevation（elevation 阴影会被半透明底透成亮度台阶）。 */
// 模糊半径和下沉量：18dp/4dp 时灰影会绕着整个形状一圈，小圆钮（44dp）等于套了个灰环，
// 边缘"高光"就被灰影吃掉了（2026-11-23 量：钮边缘 241，而页面底色 250 —— 看着比周围还暗）。
// 收紧 + 下沉，让阴影只出现在下方。
private val ShadowBlur = 14.dp
private val ShadowOffsetY = 6.dp
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
    /**
     * 高光环的颜色；null = 纯白。
     *
     * **主题色按钮也不要传主色（或其调白版）**：内部是"白玻面 + 淡色叠加"，已经很亮，
     * 任何带主色的环都比内部暗 —— 真机上是一圈灰紫的沟（2026-11-23 量了像素才认）。
     * 要的是亮边，就只能是≥白的亮度。
     */
    rimColor: Color? = null,
    /** 是否画投影。页头整条铺到屏幕顶上时不需要（会在地面留下一条阴影）。 */
    shadow: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalTavernPalette.current
    val surface = MaterialTheme.colorScheme.surface
    val pad = with(LocalDensity.current) { GlassBlur.roundToPx() }
    var nodeOrigin by remember { mutableStateOf(Offset.Zero) }

    // 按压动画：直接吃 interaction 流，**不要**用 collectIsPressedAsState()。
    //
    // 布尔状态会吞事件：快速点按时 Press 和 Release 落在同一帧里，重组只看到最终的 false，
    // 动画一次都不会播（2026-11-23 用户："有时动画不会出现"）。吃流的话每个事件都到，
    // Release 用 animateTo 打断 Press 的动画 —— Animatable 会把当前值和末速度交给新动画，
    // 所以连按是连续的（tween 会清 0 重新起跑，那一下顿挫就是不丝滑的来源）。
    val press = remember { Animatable(1f) }
    if (interactionSource != null) {
        LaunchedEffect(interactionSource) {
            var pressedAt = 0L
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        pressedAt = System.currentTimeMillis()
                        launch { press.animateTo(PressScale, Motion.PressDown) }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        val held = System.currentTimeMillis() - pressedAt
                        launch {
                            if (held < MinPressVisibleMs) delay(MinPressVisibleMs - held)
                            press.animateTo(1f, Motion.PressUp)
                        }
                    }
                }
            }
        }
    }

    // 外壳**不裁**：投影与模糊描边都要画到形状外面。
    // 缩放在外壳上：按下时投影、高光、内容一起缩，不会出现"只有图标变小"的错位。
    Box(
        modifier = modifier
            .onGloballyPositioned { nodeOrigin = it.positionInWindow() }
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
            },
    ) {
        if (glass && shadow) {
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
                            cornerRadius = cornerRadiusOf(shape),
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

        // 边缘高光：由外向内叠几层，边缘最亮、往内渐淡。
        //
        // 两个几何约束（都是量像素量出来的）：
        // ① **整条亮带画在面内侧**：描边默认以边界线为中心，外半圈落在页面底色上 ——
        //    浅色按钮上就是一条纯白描边、像贴纸（2026-11-23 用户："边缘的光是纯白的"）。
        //    所以把每层往内缩半个线宽，并只在玻璃档裁形状；轻量档的细描边不该被裁细。
        // ② 只裁不缩没用：裁掉外半圈后 1dp 的层只剩 0.5dp，肉眼就是"没有高光"。
        Box(
            Modifier
                .matchParentSize()
                .then(if (glass) Modifier.clip(shape) else Modifier)
                .drawBehind {
                    val radius = cornerRadiusOf(shape)
                    if (glass) {
                        val rimTint = rimColor ?: Color.White
                        RimLayers.forEach { (width, alpha) ->
                            val w = width.toPx()
                            val inset = w / 2f
                            drawRoundRect(
                                color = rimTint.copy(alpha = alpha),
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - w, size.height - w),
                                cornerRadius = CornerRadius(
                                    (radius.x - inset).coerceAtLeast(0f),
                                    (radius.y - inset).coerceAtLeast(0f),
                                ),
                                style = Stroke(w),
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
 * 取形状在当前尺寸下的圆角半径。
 *
 * 原来投影和描边都写死 `CornerRadius(size.height / 2f)`（胶囊专用），
 * 页头那种"只有下方两角是圆的"条形会被画成大圆角。改成从 [Shape] 的 outline 里读。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.cornerRadiusOf(shape: Shape): CornerRadius {
    val outline = shape.createOutline(size, layoutDirection, this)
    return (outline as? androidx.compose.ui.graphics.Outline.Rounded)?.roundRect?.topLeftCornerRadius
        ?: CornerRadius.Zero
}

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
