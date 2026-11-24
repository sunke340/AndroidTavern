package app.tavern

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp

/**
 * 状态栏高度。
 *
 * 页头一直铺到状态栏后面，所以每页内容让位都要"状态栏 + 页头 + 间距"。这个值原来在 7 个文件里
 * 各写一遍 `WindowInsets.statusBars.asPaddingValues().calculateTopPadding()`，现在只有一个出处。
 */
@Composable
fun statusBarTop(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/**
 * 三个底部页面共用的页头**浮层**：左边大标题，右边玻璃胶囊装的图标动作。
 *
 * 为什么是浮层（而不是页面里的一行）：页头必须画在"内容录图层"**之外**。
 * 如果胶囊画在录制范围内，它采样的图层里会带上上一帧的胶囊自己，每帧叠加就是无限套娃
 * （胶囊里能看到自己的鬼影）。所以页头跟底栏同级，都浮在录制层上面。
 *
 * 页头底下压一层"底色 → 透明"的**半透明色罩**（不是磨砂）：内容从下面滚过去时自然淡出，
 * 标题不会跟列表文字叠在一起。磨砂页头只留给聊天页（见 `GlassDetailScaffold` 的
 * `frostedHeader`）—— 一级页的内容不会一直在标题正下方滚，没必要上磨砂。
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
    // 页头一直铺到屏幕最顶上（状态栏后面），跟系统状态栏连成一片
    val statusBar = statusBarTop()
    // 色罩：到页头底边都是饱和的，只在 28dp 收尾段里化开（线性衰减会压不住标题下沿的字）
    val scrim = headerScrim(background)

    // 高度 = 状态栏 + 页头 + 收尾段：色罩一直渐透到收尾段末尾，内容才不会在页头底边"一刀切"
    Box(modifier = modifier.fillMaxWidth().height(statusBar + PageHeaderHeight + HeaderFadeTail)) {
        Box(
            Modifier
                .matchParentSize()
                .drawBehind { drawRect(brush = scrim) },
        )
        Row(
            // 标题只占页头本身的高度；多出来的 tail 专门留给渐变收尾
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusBar)
                .height(PageHeaderHeight)
                .padding(horizontal = Space.l),
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
