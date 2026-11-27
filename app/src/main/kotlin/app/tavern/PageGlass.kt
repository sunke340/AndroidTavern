package app.tavern

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer

/**
 * 「当前这一页录的那份磨砂底片」的持有者。
 *
 * 为什么需要它：层级浮层要采"它底下那一页"的内容才像玻璃，而每个页面骨架各录一份底片。
 * 多数浮层（确认框/动作单/胶囊菜单）在**页面骨架内部**渲染，直接拿得到自己的底片；
 * 但 `MainScreen` 里那个**顶层确认框**（分享导入这种"不属于任何一页"的）在页面之外 ——
 * CompositionLocal 只能读到祖先提供的值，读不到子页面的提供值，所以改成反向登记：
 * `MainScreen` 建一个空壳放进 local，页面骨架各自把"我这一页的底片"写进来。
 *
 * 为什么两个字段都是 `mutableStateOf`：登记后要让顶层浮层重组一次（只登记一次，不会每帧抖）。
 * [origin] 也必须登记 —— 玻璃采样按窗口坐标换算，原点不对会采到隔壁那一页。
 *
 * 2026-11-27 用户报"分享确认框没有用液态玻璃"：那时它是用 `glass = false` 画的（因为顶层没有底片）。
 */
internal class PageGlass {
    var frost by mutableStateOf<GraphicsLayer?>(null)
    var origin by mutableStateOf(Offset.Zero)
}

/** 由 `MainScreen` 提供；页面骨架在里面登记自己那一份底片。 */
internal val LocalPageGlass = compositionLocalOf<PageGlass?> { null }

/**
 * 页面骨架调用：把"我这一页的底片 + 窗口原点"登记给顶层浮层。
 *
 * 只做赋值（值没变就不写），所以不会引起无谓重组；没有宿主（比如单独预览某个页面）时静默跳过。
 */
@Composable
internal fun RegisterPageGlass(frost: GraphicsLayer, origin: () -> Offset) {
    val holder = LocalPageGlass.current ?: return
    SideEffect {
        if (holder.frost !== frost) holder.frost = frost
        val now = origin()
        if (holder.origin != now) holder.origin = now
    }
}
