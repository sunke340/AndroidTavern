package app.tavern

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.text.font.FontWeight

/**
 * 三个底部页面共用的页头**浮层**：左边大标题，右边玻璃胶囊装的图标动作。
 *
 * 为什么是浮层（而不是页面里的一行）：页头必须画在"内容录图层"**之外**。
 * 如果胶囊画在录制范围内，它采样的图层里会带上上一帧的胶囊自己，每帧叠加就是无限套娃
 * （胶囊里能看到自己的鬼影）。所以页头跟底栏同级，都浮在录制层上面。
 *
 * 页头底下压一层"底色 → 透明"的渐变：内容从下面滚过去时自然淡出，标题不会跟列表文字叠在一起。
 */
@Composable
fun PageHeader(
    title: String,
    glass: Boolean,
    frost: GraphicsLayer,
    contentOrigin: () -> Offset,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    val background = MaterialTheme.colorScheme.background
    val actionPress = remember { MutableInteractionSource() }

    Box(modifier = modifier.fillMaxWidth().height(PageHeaderHeight + HeaderFadeTail)) {
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    // 页头遮罩：**顶部留一点透**（0.92），内容滚到下面时能隐约透出来，读起来还是玻璃；
                    // 再低就会和标题文字打架（实底 1.0 是 2026-11-22 为了"两层文字叠加"改的，
                    // 现在底部 28dp 的渐变尾巴已经承担了过渡，顶部可以放开一点）。
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.00f to background.copy(alpha = 0.92f),
                            0.60f to background.copy(alpha = 0.90f),
                            0.86f to background.copy(alpha = 0.55f),
                            1.00f to Color.Transparent,
                        ),
                    )
                },
        )
        Row(
            // 标题只占页头本身的高度；多出来的 tail 专门留给渐变收尾
            modifier = Modifier.fillMaxWidth().height(PageHeaderHeight).padding(horizontal = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (actions != null) {
                GlassSurface(
                    frost = frost,
                    contentOrigin = contentOrigin,
                    glass = glass,
                    shape = RoundedCornerShape(Radius.pill),
                    modifier = Modifier.height(HeaderActionHeight),
                    interactionSource = actionPress,
                ) {
                    // 只吃高度、不吃宽度：写成 fillMaxSize 会把胶囊撑满整行，标题的 weight(1f)
                    // 会被挤成 0 宽（2026-11-22 真机上标题直接消失）。胶囊要"包住动作"。
                    Row(
                        modifier = Modifier.fillMaxHeight().padding(horizontal = Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompositionLocalProvider(LocalGlassPress provides actionPress) { actions() }
                    }
                }
            }
        }
    }
}
