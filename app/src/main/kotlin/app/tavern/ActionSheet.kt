package app.tavern

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** 底栏先滑走，弹层随后才上来（用户 2026-11-24 要求的先后顺序）。 */
const val SheetEnterDelayMillis = 180

/** 动作单里的一项。 */
class SheetAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** 一次动作单请求：标题 + 若干动作。 */
class SheetSpec(
    val title: String,
    val actions: List<SheetAction>,
)

/**
 * 动作单的"请求"入口。
 *
 * 为什么不让页面自己 composed 弹层：液态玻璃要把页面内容录进图层再采样，弹层必须画在
 * **录制范围之外**（和页头、底栏同级），否则会采到自己这一帧（页头胶囊"无限套娃"的坑）。
 * 所以页面只"请求"，由最近的页面骨架（`TabsRoot` / `GlassDetailScaffold`）渲染。
 */
class SheetController {
    internal var spec by mutableStateOf<SheetSpec?>(null)
        private set

    fun show(title: String, actions: List<SheetAction>) {
        spec = SheetSpec(title, actions)
    }

    fun dismiss() {
        spec = null
    }
}

/** 页面骨架提供给内容区的控制器；没有宿主时 `show()` 只是不会画出来。 */
val LocalSheetController = staticCompositionLocalOf { SheetController() }

/**
 * 底部弹层的外壳：遮罩 + 从底部滑上来的**液态玻璃板**。
 *
 * [frost] 必须是该页面录的磨砂底片（1/[FrostScale] 的内容层），弹层才能真的糊住背后的内容；
 * 所以这个函数只能在"页面骨架的浮层"里调用（录图层之外）。
 *
 * [enterDelayMillis] 用来做"底栏先滑走、弹层再上来"的先后（一级页传 [SheetEnterDelayMillis]）。
 */
@Composable
fun GlassSheetScaffold(
    visible: Boolean,
    onDismiss: () -> Unit,
    frost: GraphicsLayer,
    contentOrigin: () -> Offset,
    glass: Boolean,
    modifier: Modifier = Modifier,
    enterDelayMillis: Int = 0,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    BackHandler(enabled = visible) { onDismiss() }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Motion.Base, delayMillis = enterDelayMillis / 2)),
            exit = fadeOut(tween(Motion.Fast)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        // 遮罩不要涟漪：整屏一块水波很奇怪
                        indication = null,
                    ) { onDismiss() },
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = if (reduceMotion) {
                fadeIn(tween(Motion.Base, delayMillis = enterDelayMillis, easing = Motion.Decelerate))
            } else {
                slideInVertically(
                    tween(Motion.Slow, delayMillis = enterDelayMillis, easing = Motion.Standard),
                ) { it }
            },
            exit = if (reduceMotion) {
                fadeOut(tween(Motion.Fast))
            } else {
                slideOutVertically(tween(Motion.Base, easing = Motion.Standard)) { it }
            },
        ) {
            GlassSheetPlate(
                frost = frost,
                contentOrigin = contentOrigin,
                glass = glass,
                title = title,
                onDismiss = onDismiss,
                content = content,
            )
        }
    }
}

/** 弹层本体：上两角圆角的液态玻璃板 + 把手 + 可选标题 + 内容。 */
@Composable
private fun GlassSheetPlate(
    frost: GraphicsLayer,
    contentOrigin: () -> Offset,
    glass: Boolean,
    title: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 把手现在是真的把手（用户 2026-11-25）：按住往下拖跟手，松手看位移/速度决定收回还是弹回。
    //
    // 为什么跟手用 translationY 而不是改高度：弹层高度由内容决定，改高度会让整块重新测量，
    // 拖到一半内容就换行了。位移只落在绘制上，最便宜也最像"纸片被拖下去"。
    // 收紧的判据取**弹层自身高度**的 1/4（不是屏高）：短弹层拖过半个屏太累，长弹层又太敏感。
    val density = LocalDensity.current
    var dragY by remember { mutableFloatStateOf(0f) }
    var plateHeightPx by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta ->
        // 只允许往下拖；往上给 0（不弹出来，因为弹层高度就是内容高度，拉不出更多内容）
        dragY = (dragY + delta).coerceAtLeast(0f)
    }

    GlassSurface(
        frost = frost,
        contentOrigin = contentOrigin,
        glass = glass,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        // 弹层不是浮起的小件，投影像"托了一下"，去掉；分层靠玻璃面与遮罩
        shadow = false,
        // 面比胶囊更透一档（单独一份 spec，调这里不会连带改胶囊）
        liquid = LiquidGlassSpec.Sheet,
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { plateHeightPx = it.height.toFloat() }
            .graphicsLayer { translationY = dragY }
            // 吃掉点按：不然点在弹层上会穿给遮罩，弹层自己就关了
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { },
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            // 把手区：视觉仍是那根 36×4 的小条，但**整宽 28dp 都能拖**（4dp 高的条根本按不住）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            val closeByDrag = dragY > plateHeightPx * 0.25f
                            val closeByFling = velocity > with(density) { 800.dp.toPx() }
                            if (closeByDrag || closeByFling) {
                                // 顺势让出场动画从当前位置滑走，别再自己补一段位移（会看着像弹了两下）
                                onDismiss()
                            } else {
                                animate(dragY, 0f, animationSpec = Motion.RubberBand) { value, _ ->
                                    dragY = value
                                }
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
                )
            }
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.l, vertical = Space.m),
                )
            }
            // 内容多的时候在弹层里滚（模型列表可以有十几项）。
            // 上限按屏高算：弹层本身不能被内容顶出屏幕，拖动把手也还得够得着。
            val maxContentHeight = LocalConfiguration.current.screenHeightDp.dp * 0.62f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
            Box(Modifier.height(Space.l))
        }
    }
}

/**
 * 页面骨架里渲染动作单的地方（遮罩 + 玻璃弹层）。
 *
 * 放进骨架的最后一个孩子（压在页头、底栏之上）。
 */
@Composable
fun GlassActionSheetHost(
    controller: SheetController,
    frost: GraphicsLayer,
    contentOrigin: () -> Offset,
    glass: Boolean,
    modifier: Modifier = Modifier,
    enterDelayMillis: Int = 0,
) {
    val spec = controller.spec
    // 退出动画期间内容已经清空，留一份最后一次的，动画才画得出来
    var lastSpec by remember { mutableStateOf<SheetSpec?>(null) }
    LaunchedEffect(spec) {
        if (spec != null) lastSpec = spec
    }
    val content = spec ?: lastSpec

    GlassSheetScaffold(
        visible = spec != null,
        onDismiss = { controller.dismiss() },
        frost = frost,
        contentOrigin = contentOrigin,
        glass = glass,
        modifier = modifier,
        enterDelayMillis = enterDelayMillis,
        title = content?.title,
    ) {
        content?.actions?.forEach { action ->
            SheetActionRow(action = action) {
                // 先关弹层再执行：动作通常会弹确认框或换页，弹层留着会看到两层遮罩
                controller.dismiss()
                action.onClick()
            }
        }
    }
}

/** 动作单里的一行（纯文字；destructive 用 error 色）。 */
@Composable
private fun SheetActionRow(action: SheetAction, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onAction,
            )
            .padding(horizontal = Space.l, vertical = Space.m + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = action.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (action.destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
