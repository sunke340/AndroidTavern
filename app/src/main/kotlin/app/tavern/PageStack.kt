package app.tavern

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import app.tavern.data.log.AppLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 * 5. **动画开始前先预热新页**（2026-11-24）：新页最贵的是它自己的第一帧（组合+测量+录图层），
 *    直接起动画会让那 30–80ms 吃掉动画的前几帧。现在先让新页在"起跑位/全透明"下过一帧、
 *    动画再开始 —— 代价是响应多约 1 帧，换动画满帧。日志 `tag=nav` 的「新页首帧 Xms」
 *    就是这笔钱；它应该出现在「转场」那行**之前**。
 */
/**
 * 这一页现在是不是**栈顶**。
 *
 * 离场页为了转场会留在组合树里（`PageStack` 的既定做法），但它在视觉上已经开始滑走。
 * 这时页面里的"浮在窗口上的东西"（输入框光标、选文字手柄、系统键盘）**不会跟着 graphicsLayer 位移**，
 * 会钉在原处直到转场结束（用户 2026-11-25 报的）。页面可以读这个值，在离场那一帧把它们收掉。
 */
val LocalPageIsCurrent = staticCompositionLocalOf { true }

@Composable
internal fun <S> PageStack(
    target: Destination,
    /** 当前 UI 状态。离场页会拿到它**还是当前页时**的那一份快照（见下面 [snapshots]）。 */
    state: S,
    depthOf: (Destination) -> Int,
    mode: NavAnimation,
    modifier: Modifier = Modifier,
    /** 离场页被真正移除时回调（给 `nav` 日志用，验证转场有没有跑满）。 */
    onRemoved: (Destination) -> Unit = {},
    content: @Composable (Destination, S) -> Unit,
) {
    // 参与绘制的页面（按加入顺序）。当前页永远在最后一个。
    val pages: SnapshotStateList<Destination> = remember { mutableStateListOf(target) }
    var forward by remember { mutableStateOf(true) }
    /** 转场动画是否正在跑；false 时只画原来那页（见下面"还没开始转场"那段）。 */
    var running by remember { mutableStateOf(false) }
    /**
     * 预热帧：新页已经入栈、动画还没开始。这一两帧里只画旧页，新页摆到 p=0 的起跑位
     * （滑入的屏外 / 减动效的全透明）—— 让新页最贵的第一帧（组合+测量+录图层）落在
     * 动画**之前**，而不是吃掉动画的前几帧。
     */
    var warming by remember { mutableStateOf(false) }
    val progress = remember { Animatable(1f) }
    val latestOnRemoved by rememberUpdatedState(onRemoved)

    // 每一页"作为当前页时的最后一份状态"。
    //
    // **这是必需的，不是优化**：页面读的是全局 UiState，而返回是把状态一起改掉的
    // （`closeDetail()` 同时清掉 detailCardId 并把 destination 换回 Tabs）。离开的那页
    // 如果继续读实时状态，它会读到"卡 id 已经没了" → 自己的 `?: return` 直接渲染空
    // → 观感就是"退场页凭空消失"（2026-11-24 录屏抓到的就是这个）。
    // 每帧只给**当前页**写快照，所以离开那页留在快照里的就是"它还是当前页时"的值。
    val snapshots = remember { mutableStateMapOf<Destination, S>() }
    SideEffect { snapshots[target] = state }

    LaunchedEffect(target) {
        val from = pages.last()
        if (from == target) return@LaunchedEffect
        forward = depthOf(target) >= depthOf(from)
        // ⚠️ 顺序不能反：先把进度归零、再让旧页静止（running=false）、然后新页入栈，
        // 预热完才打开 running。这些写在同一帧里提交，所以下一帧看到的是正确的起点。
        //
        // 反过来的代价（2026-11-24 用户报的"页面突然跳到 -1/4 再复位"）：
        // 如果 pages 先加新页、而 running/进度还是上一轮的值（progress = 1），
        // 那离场页会先用 p=1 的终态渲染一帧 —— 进方向是 `-w/4`、返回是 `+w` 的一帧闪现，
        // 肉眼看就是"整屏跳一下"，也就是用户说的闪烁。
        progress.snapTo(0f)
        running = false
        pages.add(target)
        val animating = mode != NavAnimation.None
        var warmMs = 0L
        if (animating) {
            // 预热一帧：等**新页自己的第一帧**跑完（组合 + 测量 + 绘制），再开始动画。
            // 不预热的话，新页的首帧（页面越重越久）会把 280ms 的转场吃掉几帧 ——
            // 看起来就是"页面切换丢了几帧动画"（用户 2026-11-24 报）。
            // 代价是"点下去到动画启动"多约 1 帧，比动画中途卡一下便宜。
            val warmStart = SystemClock.elapsedRealtime()
            var lastFrame = 0L
            warming = true
            try {
                // 记下走这几帧时**最长的那一帧**：它就是"新页首帧"的真实成本，
                // 和转场那行的「最长帧」同一把尺子，可以直接对比。
                repeat(2) {
                    withFrameNanos { now ->
                        if (lastFrame != 0L) warmMs = maxOf(warmMs, (now - lastFrame) / 1_000_000)
                        lastFrame = now
                    }
                }
            } finally {
                warming = false
            }
            AppLog.i(
                "nav",
                "转场预热 ${mode.name} 新页首帧 ${warmMs}ms（等 ${SystemClock.elapsedRealtime() - warmStart}ms）",
            )
        }
        running = animating
        if (running) {
            // 转场期间的帧统计：一次长帧（解析/GC/着色器编译）会把 280ms 的动画直接吃掉，
            // 事后再猜是"哪一步卡"很难，所以把"最长帧"直接写进日志（真机没有 logcat）。
            val startedAt = SystemClock.elapsedRealtime()
            val stats = launch {
                var last = 0L
                var maxGap = 0L
                var frames = 0
                while (running) {
                    withFrameNanos { now ->
                        if (last != 0L) maxGap = maxOf(maxGap, (now - last) / 1_000_000)
                        last = now
                        frames += 1
                    }
                }
                AppLog.i(
                    "nav",
                    "转场 ${mode.name} 用时 ${SystemClock.elapsedRealtime() - startedAt}ms" +
                        "，帧 $frames，最长帧 ${maxGap}ms",
                )
            }
            // 淡入（减动效）与位移共用同一条时长曲线，保证两档切换时节奏一致
            progress.animateTo(1f, tween(Motion.Slow, easing = Motion.Decelerate))
            running = false
            // 统计协程要等下一帧才醒；给个上限，避免它卡住转场收尾
            withTimeoutOrNull(500) { stats.join() }
        }
        // 只留下目标页（从头删，目标页永远在最后，所以它的组合不会被丢掉）
        while (pages.size > 1) pages.removeAt(0)
        snapshots.keys.retainAll(pages.toSet())
        latestOnRemoved(from)
    }

    Box(modifier.fillMaxSize()) {
        val p = progress.value
        // 还没开始转场（目标刚变、效应还没跑，或者第一帧）：**只画原来那页、不做任何变换**。
        // 这一帧的画面与点击前完全一致，所以不会闪；转场从下一帧开始。
        val visible = if (running || warming) pages else listOf(pages.last())
        visible.forEach { dest ->
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
                            // 预热帧按 p=0 摆位：新页停在动画的起点（屏外/透明），旧页保持原样。
                            val q = if (warming) 0f else p
                            if (!running && !(warming && isIncoming)) {
                                // 没在转场时一律"原样"：位置 0、不透明。
                                // （不这么写的话，第一帧会拿上一轮残留的 p=1 去算 alpha/位移 → 闪一下空白。）
                                alpha = 1f
                                translationX = 0f
                            } else if (mode == NavAnimation.Fade) {
                                // 减动效：只淡化，不位移
                                alpha = if (isIncoming) q else 1f - q
                                translationX = 0f
                            } else if (isIncoming) {
                                // 进来那页：进 = 从右滑入；返回 = 从左侧 1/4 归位
                                alpha = 1f
                                translationX = if (forward) w * (1f - q) else -w / 4f * (1f - q)
                            } else {
                                // 退场那页：进 = 左移 1/4（视差）；返回 = 整屏向右滑走
                                alpha = 1f
                                translationX = if (forward) -w / 4f * q else w * q
                            }
                        },
                ) {
                    // 当前页用实时状态；离场页用它自己那份快照
                    CompositionLocalProvider(LocalPageIsCurrent provides (dest == target)) {
                        content(dest, if (dest == target) state else snapshots[dest] ?: state)
                    }
                }
            }
        }
    }
}
