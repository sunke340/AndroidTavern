package app.tavern

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.dp

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
 * 页面骨架里渲染动作单的地方：遮罩 + 底部玻璃弹层。
 *
 * [frost] 就是该页面的磨砂底片（1/[FrostScale] 的内容层），所以弹层能真的糊住背后的内容，
 * 而不是"半透明白板"。放进骨架的最后一个孩子（压在页头、底栏之上）。
 */
@Composable
fun GlassActionSheetHost(
    controller: SheetController,
    frost: GraphicsLayer,
    contentOrigin: () -> Offset,
    glass: Boolean,
    modifier: Modifier = Modifier,
) {
    val spec = controller.spec
    val reduceMotion = rememberReduceMotion()
    // 退出动画期间内容已经清空，留一份最后一次的，动画才画得出来
    var lastSpec by remember { mutableStateOf<SheetSpec?>(null) }
    LaunchedEffect(spec) {
        if (spec != null) lastSpec = spec
    }
    val content = spec ?: lastSpec

    BackHandler(enabled = spec != null) { controller.dismiss() }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = spec != null,
            enter = fadeIn(tween(Motion.Base)),
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
                    ) { controller.dismiss() },
            )
        }

        AnimatedVisibility(
            visible = spec != null,
            enter = if (reduceMotion) {
                fadeIn(tween(Motion.Base, easing = Motion.Decelerate))
            } else {
                slideInVertically(tween(Motion.Slow, easing = Motion.Standard)) { it }
            },
            exit = if (reduceMotion) {
                fadeOut(tween(Motion.Fast))
            } else {
                slideOutVertically(tween(Motion.Base, easing = Motion.Standard)) { it }
            },
        ) {
            content?.let { current ->
                GlassSheet(
                    spec = current,
                    frost = frost,
                    contentOrigin = contentOrigin,
                    glass = glass,
                    onAction = { action ->
                        // 先关弹层再执行：动作通常会弹确认框或换页，弹层留着会看到两层遮罩
                        controller.dismiss()
                        action.onClick()
                    },
                )
            }
        }
    }
}

/** 弹层本体：上两角圆角的液态玻璃板 + 把手 + 标题 + 动作行。 */
@Composable
private fun GlassSheet(
    spec: SheetSpec,
    frost: GraphicsLayer,
    contentOrigin: () -> Offset,
    glass: Boolean,
    onAction: (SheetAction) -> Unit,
) {
    GlassSurface(
        frost = frost,
        contentOrigin = contentOrigin,
        glass = glass,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        // 弹层不是浮起的小件，投影像"托了一下"，去掉；分层靠玻璃面与遮罩
        shadow = false,
        modifier = Modifier
            .fillMaxWidth()
            // 吃掉点按：不然点在弹层上会穿给遮罩，弹层自己就关了
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { },
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            Box(
                modifier = Modifier
                    .padding(top = Space.s)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
            )
            Text(
                text = spec.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.l, vertical = Space.m),
            )
            spec.actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onAction(action) }
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
            Box(Modifier.height(Space.l))
        }
    }
}
