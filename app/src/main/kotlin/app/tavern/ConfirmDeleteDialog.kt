package app.tavern

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.dp
import app.tavern.data.log.AppLog

/**
 * 一次确认请求。用**控制器 + 骨架宿主**而不是 `AlertDialog`：
 * `AlertDialog` 是独立的系统窗口，采不到页面内容层，做不成液态玻璃（2026-11-25 用户要求
 * 删除确认也要玻璃）。这和动作单是同一个约束、同一个解法。
 */
class ConfirmSpec(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
    /**
     * 可选的第三个按钮（放在"确认"左边）。
     *
     * 用在"保存角色卡：新建 / 覆盖 / 取消"这种**两个正事**的场合 —— 和删除确认共用同一个
     * 玻璃框，而不是另做一套弹窗（用户 2026-11-25 第 7 条：保存不要用抽屉，要像删除确认）。
     */
    val secondaryLabel: String? = null,
    val onSecondary: (() -> Unit)? = null,
    /** 确认按钮用红（删除类）还是主色（保存类）。 */
    val destructive: Boolean = true,
)

/** 确认框的"请求"入口：页面只 ask，由页面骨架渲染（[LocalConfirmController]）。 */
class ConfirmController {
    internal var spec by mutableStateOf<ConfirmSpec?>(null)
        private set

    /** 是否有页面骨架接了宿主；没接时 `ask()` 会静默落空（同 [SheetController.attached]）。 */
    internal var attached = false

    fun ask(spec: ConfirmSpec) {
        if (!attached) {
            AppLog.w("ui", "确认框请求没有宿主：${spec.title}（页面要传 confirmController 或读骨架内的 Local）")
        }
        this.spec = spec
    }

    /** 取消：清掉自己再回调，避免回调里又 ask 时被覆盖。 */
    fun dismiss() {
        val current = spec ?: return
        spec = null
        AppLog.i("ui", "确认框取消：${current.title}")
        current.onDismiss()
    }

    /** 第三个按钮（如"新建"）：先摘掉弹层，再执行动作。 */
    internal fun secondary() {
        val current = spec ?: return
        spec = null
        AppLog.i("ui", "确认框第三选：${current.secondaryLabel}")
        current.onSecondary?.invoke()
    }

    /** 确认：先摘掉弹层（它会播退出动画），再执行动作。 */
    internal fun confirm() {
        val current = spec ?: return
        spec = null
        AppLog.i("ui", "确认框确认：${current.title}")
        current.onConfirm()
    }
}

/** 页面骨架提供给内容区的控制器；没有宿主时 `ask()` 只是不会画出来。 */
val LocalConfirmController = staticCompositionLocalOf { ConfirmController() }

/**
 * 骨架里渲染确认框的地方（遮罩 + 居中玻璃板）。
 *
 * 必须放在**录图层之外**（和页头、动作单同级），否则玻璃会采到自己这一帧。
 * 放进骨架最后一个孩子 = 压在页头、底栏、动作单之上。
 */
@Composable
fun GlassConfirmHost(
    controller: ConfirmController,
    frost: GraphicsLayer,
    contentOrigin: () -> Offset,
    glass: Boolean,
    modifier: Modifier = Modifier,
) {
    val spec = controller.spec
    SideEffect { controller.attached = true }
    DisposableEffect(controller) { onDispose { controller.attached = false } }
    // 退出动画期间 spec 已经清空，留一份最后一次的内容，动画才画得出来
    var lastSpec by remember { mutableStateOf<ConfirmSpec?>(null) }
    LaunchedEffect(spec) {
        if (spec != null) lastSpec = spec
    }
    val content = spec ?: lastSpec
    val visible = spec != null
    // 确认框是删数据的最后一道闸：弹没弹、点了哪个，日志里要能分辨
    LaunchedEffect(visible, content?.title) {
        if (visible) AppLog.i("ui", "确认框显示：${content?.title ?: ""}")
    }
    val reduceMotion = rememberReduceMotion()

    BackHandler(enabled = visible) { controller.dismiss() }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Motion.Base)),
            exit = fadeOut(tween(Motion.Fast)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { controller.dismiss() },
            )
        }

        AnimatedVisibility(
            visible = visible && content != null,
            enter = if (reduceMotion) {
                fadeIn(tween(Motion.Base, easing = Motion.Decelerate))
            } else {
                fadeIn(tween(Motion.Base, easing = Motion.Decelerate)) +
                    scaleIn(tween(Motion.Base, easing = Motion.Decelerate), initialScale = 0.92f)
            },
            exit = if (reduceMotion) {
                fadeOut(tween(Motion.Fast))
            } else {
                fadeOut(tween(Motion.Fast)) + scaleOut(tween(Motion.Fast), targetScale = 0.94f)
            },
        ) {
            val shown = content ?: return@AnimatedVisibility
            GlassSurface(
                frost = frost,
                contentOrigin = contentOrigin,
                glass = glass,
                shape = RoundedCornerShape(Radius.pill),
                // 确认框是浮起来的小件，投影与面都不该太抢：和弹层同一档材质
                liquid = LiquidGlassSpec.Sheet,
                modifier = Modifier
                    .padding(horizontal = Space.xl)
                    .widthIn(max = 380.dp)
                    // 吃掉点按：不然点框里也会穿给遮罩，框自己就没了
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { },
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(Space.xl)) {
                    Text(shown.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Space.m))
                    Text(
                        text = shown.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Space.l))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { controller.dismiss() }) { Text("取消") }
                        val secondary = shown.secondaryLabel
                        if (secondary != null) {
                            TextButton(onClick = { controller.secondary() }) { Text(secondary) }
                        }
                        // 不可逆操作里，这是唯一需要眼睛停一下的地方
                        TextButton(onClick = { controller.confirm() }) {
                            Text(
                                shown.confirmLabel,
                                color = if (shown.destructive) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 不可逆删除的统一确认框：标题 + 一句后果 + 红色确认。
 *
 * **签名故意保持不变**（8 处调用点不用改）：它现在只是把请求交给 [LocalConfirmController]，
 * 真正的界面由页面骨架的 [GlassConfirmHost] 画在录图层之外，所以是液态玻璃。
 *
 * 调用时**参数全部写名字**：`onConfirm` 与 `onDismiss` 都是 lambda，位置写反了编译器不报。
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "删除",
    /**
     * 页面级确认框要传骨架里那个控制器：默认读的是 CompositionLocal，页面在骨架之前
     * 调用本函数就会拿到"没有宿主"的实例（点了什么都不弹）。
     */
    controller: ConfirmController = LocalConfirmController.current,
) {
    // 回调可能随重组换新实例，用 rememberUpdatedState 保证弹层里调的是最新那个
    val latestConfirm by rememberUpdatedState(onConfirm)
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(title, message, confirmLabel) {
        controller.ask(
            ConfirmSpec(
                title = title,
                message = message,
                confirmLabel = confirmLabel,
                onConfirm = { latestConfirm() },
                onDismiss = { latestDismiss() },
            ),
        )
    }
}

/**
 * 两个正事 + 取消的确认框（磨砂玻璃，和删除确认同一个框）。
 *
 * 用在「保存角色卡」：确认 = 覆盖原卡，第三个按钮 = 另存为新卡。
 * 参数**全部写名字**：这里有两个 lambda，位置写反编译器不报。
 */
@Composable
fun ConfirmChoiceDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    onDismiss: () -> Unit,
    controller: ConfirmController = LocalConfirmController.current,
) {
    val latestConfirm by rememberUpdatedState(onConfirm)
    val latestSecondary by rememberUpdatedState(onSecondary)
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(title, message, confirmLabel, secondaryLabel) {
        controller.ask(
            ConfirmSpec(
                title = title,
                message = message,
                confirmLabel = confirmLabel,
                onConfirm = { latestConfirm() },
                onDismiss = { latestDismiss() },
                secondaryLabel = secondaryLabel,
                onSecondary = { latestSecondary() },
                destructive = false,
            ),
        )
    }
}
