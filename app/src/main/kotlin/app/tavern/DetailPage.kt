package app.tavern

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize

/** 二级/三级页的页头高度（不含状态栏）。比一级页的 72dp 矮一档。 */
val DetailHeaderHeight: Dp = 56.dp

/** 页头圆形返回钮的直径。 */
val DetailBackSize: Dp = 40.dp

/** 页头玻璃胶囊的高度（比一级页的 44dp 矮一档）。 */
val DetailActionHeight: Dp = 40.dp

/**
 * 二级/三级页骨架：**内容录图层 + 玻璃页头浮层**。
 *
 * 页头形状参考系统相机那种：左边一个**圆形玻璃返回钮**，跟着是大标题（可带一行副标题），
 * 右边一个**玻璃胶囊**装图标动作。
 *
 * 为什么每个二级页要自己录一份图层：这些页在 `TabsRoot` 之外，一级页那份图层到这里就没有了。
 * 反过来说这正好避免了切页时两页抢同一个图层 —— 每页只采样自己的内容。
 *
 * 页头仍然是浮层（画在录制之外），理由同一级页：画在里面胶囊会采样到上一帧的自己。
 */
@Composable
fun GlassDetailScaffold(
    title: String,
    onBack: () -> Unit,
    glass: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /**
     * 整条页头是否**磨砂**。默认 false = 半透明色罩（和一级页一致）。
     *
     * 只有聊天页传 true：那页的消息一直在标题正下方滚，必须糊掉才不会跟标题打架。
     * 其余二级页（设置、角色详情、档案编辑）里内容不在标题下面滚，色罩足够，磨砂反而显脏。
     */
    frostedHeader: Boolean = false,
    /** 内容区顶部让位。传 0 表示页面自己用 `contentPadding` 让位，这样内容能滚到玻璃页头下面。 */
    contentTopInset: Dp = DetailHeaderHeight,
    actions: (@Composable () -> Unit)? = null,
    snackbarHost: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    /**
     * 底部浮层（聊天输入区）。它画在**录制之外**，所以能拿 [GlassBackdrop] 做真玻璃：
     * 磨砂采样的是页面内容，消息能从胶囊后面滚过去。
     */
    bottomOverlay: (@Composable BoxScope.(GlassBackdrop) -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val layer = rememberGraphicsLayer()
    // 动作单宿主：二级页自己的录图层（frost）喂给弹层，弹的才是这一页的内容
    val sheet = remember { SheetController() }
    // 确认框宿主：同一个理由（AlertDialog 是独立窗口，采不到内容层，做不成玻璃）
    val confirm = remember { ConfirmController() }
    val frost = rememberGraphicsLayer()
    val origin = remember { mutableStateOf(Offset.Zero) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 键盘高度也要让位：不然列表被键盘盖住、又滚不出来（用户 2026-11-23 报）。
    // 注意是**并集**不是相加：键盘弹出时导航栏区域本来就被键盘盖住了，两个数相加会多抬一个导航栏高度。
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val statusBar = statusBarTop()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = background,
        snackbarHost = snackbarHost,
        bottomBar = bottomBar,
        // 页头自己吃状态栏留白（它画在录制层上面），这里不让 Scaffold 再加一层。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    // 内容铺到状态栏后面：页头（含状态栏）磨砂盖住它，滚过去的内容会被糊掉
                    .onGloballyPositioned { origin.value = it.positionInWindow() }
                    .drawWithContent {
                        if (glass) {
                            layer.record(size = size.toIntSize()) {
                                drawRect(background)
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(layer)
                            recordFrost(frost, layer, size.toIntSize())
                        } else {
                            drawContent()
                        }
                    },
            ) {
                CompositionLocalProvider(
                    LocalSheetController provides sheet,
                    LocalConfirmController provides confirm,
                ) {
                    content(
                        PaddingValues(
                            top = contentTopInset + statusBar,
                            bottom = inner.calculateBottomPadding() + maxOf(navBottom, imeBottom),
                        ),
                    )
                }
            }

            if (bottomOverlay != null) {
                // 也要拿到同一份控制器：输入区的模型抽屉、动作单都从这里弹（以前这里在
                // 控制器作用域之外 —— 在那里 show() 只会落到"没有宿主"的默认实例上，静悄悄不弹）
                CompositionLocalProvider(
                    LocalSheetController provides sheet,
                    LocalConfirmController provides confirm,
                ) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(
                                WindowInsets.systemBars
                                    .only(WindowInsetsSides.Bottom)
                                    .union(WindowInsets.ime),
                            ),
                    ) {
                        bottomOverlay(GlassBackdrop(frost, { origin.value }))
                    }
                }
            }

            // 页头的动作（⋮ 菜单）同样要点得开抽屉/确认框
            CompositionLocalProvider(
                LocalSheetController provides sheet,
                LocalConfirmController provides confirm,
            ) {
                DetailHeader(
                    title = title,
                    subtitle = subtitle,
                    onBack = onBack,
                    glass = glass,
                    frosted = frostedHeader,
                    frost = frost,
                    contentOrigin = { origin.value },
                    actions = actions,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            // 压在页头与输入区之上
            GlassActionSheetHost(
                controller = sheet,
                frost = frost,
                contentOrigin = { origin.value },
                glass = glass,
            )

            // 确认框压在最上面一档（它通常从动作单里发起，弹层刚收就出现）
            GlassConfirmHost(
                controller = confirm,
                frost = frost,
                contentOrigin = { origin.value },
                glass = glass,
            )
        }
    }
}

/** 圆形返回钮 + 大标题 + 玻璃胶囊动作。 */
@Composable
private fun DetailHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    glass: Boolean,
    frosted: Boolean,
    frost: GraphicsLayer,
    contentOrigin: () -> Offset,
    actions: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    // 页头铺到屏幕最顶上（状态栏后面），跟系统状态栏连成一片
    val statusBar = statusBarTop()
    // 按压来源：缩放/压暗在 GlassSurface 上，点击在里面的 Box 上，两者共用同一个 source
    val backPress = remember { MutableInteractionSource() }
    val actionPress = remember { MutableInteractionSource() }
    // 磨砂条收到页头底边就结束；半透明色罩还要往下多一段收尾，内容才不会一刀切地出现
    val useBand = frosted && glass
    val tail = if (useBand) 0.dp else HeaderFadeTail
    // 色罩：到页头底边都是饱和的，只在收尾段里化开
    val scrim = headerScrim(background)

    Box(modifier = modifier.fillMaxWidth().height(statusBar + DetailHeaderHeight + tail)) {
        if (useBand) {
            GlassSurface(
                frost = frost,
                contentOrigin = contentOrigin,
                glass = true,
                // **直角**：页头不是胶囊。下两角留圆角的话，下沿那条亮线会在两端往上翘，
                // 看起来就是"大胶囊的下半圈"（2026-11-23 用户指出）。
                shape = RectangleShape,
                // 投影只投在下沿（整条横条四周均匀投影没有意义，还会透过半透明面把整条糊灰）
                shadow = true,
                shadowBottomOnly = true,
                // 页头下沿的影子压一半：它正对着消息正文，整强度会一直抢注意力
                // （用户 2026-11-24："页头下方阴影存在感有点强"）
                shadowScale = 0.5f,
                // 整条页头也走液态玻璃，但**只在下沿画一道直的亮线**：
                // 四边都亮就成了一颗大胶囊（2026-11-23 用户指出，深色模式下尤其明显）。
                liquid = LiquidGlassSpec.HeaderBand,
                modifier = Modifier.matchParentSize(),
            ) {
                // 玻璃面本身不带内容：标题与按钮压在上面
            }
        } else {
            Box(Modifier.matchParentSize().drawBehind { drawRect(brush = scrim) })
        }
        Row(
            // 标题只占页头本身的高度；多出来的 tail 专门留给渐变收尾
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusBar)
                .height(DetailHeaderHeight)
                .padding(horizontal = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassSurface(
                frost = frost,
                contentOrigin = contentOrigin,
                glass = glass,
                shape = CircleShape,
                modifier = Modifier.size(DetailBackSize),
                interactionSource = backPress,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().clickable(
                        interactionSource = backPress,
                        indication = null,
                        onClick = onBack,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.width(Space.m))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            if (actions != null) {
                GlassSurface(
                    frost = frost,
                    contentOrigin = contentOrigin,
                    glass = glass,
                    // 二级页只有一个动作，做成正圆（之前是胶囊，宽度大于高度看着像圆角矩形）
                    shape = CircleShape,
                    modifier = Modifier.size(DetailActionHeight),
                    interactionSource = actionPress,
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CompositionLocalProvider(LocalGlassPress provides actionPress) { actions() }
                    }
                }
            }
        }
    }
}

