package app.tavern

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val frost = rememberGraphicsLayer()
    val origin = remember { mutableStateOf(Offset.Zero) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

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
                    .statusBarsPadding()
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
                content(
                    PaddingValues(
                        top = contentTopInset,
                        bottom = inner.calculateBottomPadding() + navBottom,
                    ),
                )
            }

            if (bottomOverlay != null) {
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

            DetailHeader(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                glass = glass,
                frost = frost,
                contentOrigin = { origin.value },
                actions = actions,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
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
    frost: GraphicsLayer,
    contentOrigin: () -> Offset,
    actions: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background

    Box(modifier = modifier.fillMaxWidth().height(DetailHeaderHeight + HeaderFadeTail)) {
        // 内容从下面滚过去时淡出，标题不会和内容叠在一起
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    // 标题区域必须是实底，否则内容滚到页头下面时两层文字会叠在一起
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.00f to background,
                            0.62f to background,
                            0.88f to background.copy(alpha = 0.55f),
                            1.00f to Color.Transparent,
                        ),
                    )
                },
        )
        Row(
            // 标题只占页头本身的高度；多出来的 tail 专门留给渐变收尾
            modifier = Modifier.fillMaxWidth().height(DetailHeaderHeight).padding(horizontal = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassSurface(
                frost = frost,
                contentOrigin = contentOrigin,
                glass = glass,
                shape = CircleShape,
                modifier = Modifier.size(DetailBackSize),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().pressFeedback(onClick = onBack),
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
                    shape = RoundedCornerShape(Radius.pill),
                    modifier = Modifier.height(DetailActionHeight),
                ) {
                    Row(
                        modifier = Modifier.fillMaxHeight().padding(horizontal = Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { actions() }
                }
            }
        }
    }
}
