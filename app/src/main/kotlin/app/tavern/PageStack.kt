package app.tavern

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex

/**
 * 页面栈转场：**自己管叠放次序**，不再用 `AnimatedContent`。
 *
 * 为什么扔掉 `AnimatedContent`（2026-11-23 结案，踩了三轮）：
 * 它给每个可见内容套的 zIndex 取值是"这一段转场的 `ContentTransform.targetContentZIndex`"，
 * 两个可见内容拿到的是同一个值 → 必然打平；打平后由放置顺序决定，而 target 永远放在最后
 * → **进来那页永远在最上面**。想靠 `targetContentZIndex` 让"退场那页（深）盖在上面"是做不到的：
 * 单一值只能让两层一起抬或一起压。
 * （之所以还见过一次"像是生效了"，是因为当时退场页同时带整屏位移，被读成了"抽走"。）
 *
 * Navigation 3 的 `NavDisplay` 也是在 `AnimatedContent` 上自己维护 zIndex 表才做到的，
 * 而它要 Compose 1.10（我们锁在 1.7.6），所以这里按同样的思路写了个最小实现：
 *
 * 1. **离场页保留在组合树里**（用 `key(dest)` 分组），它的滚动位置、状态都不会丢；
 * 2. **叠放次序由方向决定**：进 → 新页在上；返回 → 旧页（深）在上，整屏滑走；
 * 3. 位移成对：进 = 新页从右滑入 + 旧页左移 1/4；返回 = 旧页整屏右滑走 + 新页从 -1/4 归位。
 *    两个 1/4 是同一个量，读起来才是同一张纸在动。
 * 4. 动画只做在"进来那页"和"退场那页"的 `graphicsLayer.translationX` 上，
 *    整页不会重新量尺寸（这正是当年 `AnimatedContent` 的 `SizeTransform` 要关掉的东西）。
 */
@Composable
internal fun PageStack(
    target: Destination,
    depthOf: (Destination) -> Int,
    mode: NavAnimation,
    modifier: Modifier = Modifier,
    /** 离场页被真正移除时回调（给 `nav` 日志用，验证转场有没有跑满）。 */
    onRemoved: (Destination) -> Unit = {},
    content: @Composable (Destination) -> Unit,
) {
    // 参与绘制的页面（按加入顺序）。当前页永远在最后一个。
    val pages: SnapshotStateList<Destination> = remember { mutableStateListOf(target) }
    var forward by remember { mutableStateOf(true) }
    val progress = remember { Animatable(1f) }
    val latestOnRemoved by rememberUpdatedState(onRemoved)

    LaunchedEffect(target) {
        if (pages.last() == target) return@LaunchedEffect
        val from = pages.last()
        forward = depthOf(target) >= depthOf(from)
        pages.add(target)
        if (mode == NavAnimation.None) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            // 淡入（减动效）与位移共用同一条时长曲线，保证两档切换时节奏一致
            progress.animateTo(1f, tween(Motion.Slow, easing = Motion.Decelerate))
        }
        // 只留下目标页（从头删，目标页永远在最后，所以它的组合不会被丢掉）
        while (pages.size > 1) pages.removeAt(0)
        latestOnRemoved(from)
    }

    Box(modifier.fillMaxSize()) {
        val p = if (mode == NavAnimation.None) 1f else progress.value
        pages.forEach { dest ->
            key(dest) {
                val isIncoming = dest == target
                // 叠放：进 = 新页在上；返回 = 旧页（深）在上
                val onTop = if (forward) isIncoming else !isIncoming
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (onTop) 1f else 0f)
                        .graphicsLayer {
                            val w = size.width
                            if (mode == NavAnimation.Fade) {
                                // 减动效：只淡化，不位移
                                alpha = if (isIncoming) p else 1f - p
                            } else if (isIncoming) {
                                // 进来那页：进 = 从右滑入；返回 = 从左侧 1/4 归位
                                translationX = if (forward) w * (1f - p) else -w / 4f * (1f - p)
                            } else {
                                // 退场那页：进 = 左移 1/4（视差）；返回 = 整屏向右滑走
                                translationX = if (forward) -w / 4f * p else w * p
                            }
                        },
                ) {
                    content(dest)
                }
            }
        }
    }
}
