package app.tavern

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tavern.data.log.AppLog

/**
 * 顶层结构：三栏（会话 / 角色 / 设置），其余都是叠在上面的二级页。
 *
 * 导航用一个 [Destination] 状态 + `AnimatedContent` 表达，而不是 early return：
 * 后者在换页时是"整棵树直接替换"，没法做进/出动画。
 *
 * 深度决定动画方向：深→更深 从右侧进；变浅 反向退出。
 */
private sealed interface Destination {
    data object Tabs : Destination
    data object Chat : Destination
    data class Settings(val page: SettingsPage) : Destination
    data class Detail(val cardId: String) : Destination
    data class CardConfig(val cardId: String, val page: CardConfigPage) : Destination
    /** 设置 → 模型 → 某条档案（三级）。 */
    data object ProfileEdit : Destination
    /** 设置 → 世界书 → 某一本（三级）。 */
    data class WorldBookEntries(val name: String) : Destination
}

private fun depthOf(destination: Destination): Int = when (destination) {
    Destination.Tabs -> 0
    is Destination.Settings -> 1
    is Destination.Detail -> 1
    Destination.Chat -> 2
    is Destination.CardConfig -> 2
    Destination.ProfileEdit -> 2
    is Destination.WorldBookEntries -> 2
}

/** 日志里的目标名：带页面枚举名，不带卡 id（id 排障没用，还占版面）。 */
private fun labelOf(destination: Destination): String = when (destination) {
    Destination.Tabs -> "tabs"
    Destination.Chat -> "chat"
    is Destination.Settings -> "settings/${destination.page.name}"
    is Destination.Detail -> "detail"
    is Destination.CardConfig -> "card-config/${destination.page.name}"
    Destination.ProfileEdit -> "profile-edit"
    is Destination.WorldBookEntries -> "worldbook"
}

@Composable
fun MainScreen(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    TavernTheme(mode = state.themeMode, preset = state.themePreset) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // 先取到局部变量：state 是委托属性，属性访问不能智能转换
            val detailId = state.detailCardId
            val cardConfig = state.cardConfigPage
            val settingsPage = state.settingsPage
            val worldBook = state.worldBookView
            val editingProfile = state.profileDraft != null

            val destination: Destination = when {
                state.chatOpen -> Destination.Chat
                detailId != null && cardConfig != null -> Destination.CardConfig(detailId, cardConfig)
                detailId != null -> Destination.Detail(detailId)
                // 档案编辑页叠在"模型列表"之上：返回时回到列表，所以 settingsPage 不清
                editingProfile -> Destination.ProfileEdit
                // 世界书条目页叠在"设置→世界书"之上：返回落在书单
                worldBook != null -> Destination.WorldBookEntries(worldBook.name)
                settingsPage != null -> Destination.Settings(settingsPage)
                else -> Destination.Tabs
            }

            // 列表滚动位置提到这一层：换二级页时 Tabs 整棵子树会被销毁重建，
            // 状态留在 TabsRoot 里就会丢（真机上表现为"返回后列表回到顶部"）。
            val sessionsList = rememberLazyListState()
            val charactersGrid = rememberLazyGridState()

            // 导航打点：每次换页留一行。日志是唯一的真机证据来源，没这行就只能靠猜
            // （2026-11-22 那次导出的日志只有一行启动记录，什么都没法定位）。
            val lastDepth = remember { intArrayOf(-1) }
            // 换页发生的时刻：页面被移除时拿它算"存活了多久"，用来判断转场到底跑没跑。
            val navAt = remember { longArrayOf(0L) }
            LaunchedEffect(destination) {
                val depth = depthOf(destination)
                val previous = lastDepth[0]
                val direction = when {
                    previous < 0 -> "init"
                    depth > previous -> "push"
                    depth < previous -> "pop"
                    else -> "switch"
                }
                navAt[0] = System.currentTimeMillis()
                AppLog.i(
                    "nav",
                    "${labelOf(destination)} depth=$depth $direction anim=${state.navAnimation.name}",
                )
                lastDepth[0] = depth
            }

            AnimatedContent(
                targetState = destination,
                // 必须钉死整屏：不写这一句，AnimatedContent 会按内容量尺寸，
                // 而它与默认开启的 SizeTransform 组合起来会把换页变成"新页面从 0 长到满屏"。
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    // 叠放次序由深度决定：深的总在上面。
                    //
                    // 不设 zIndex 时 AnimatedContent 默认把**新进来的内容放在最上层**
                    // （官方文档原话：among content with the same zIndex, the incoming
                    // target content will be on top, as it will be placed last），
                    // 于是"返回"变成：上一级（浅的那页）从上层滑进来，盖住正在退场的当前页，
                    // 两层朝同一方向错位滑动 —— 观感就成了"从右下角弹出来"。
                    // 设了 zIndex，返回时深度更大的当前页天然在顶层，才是"把一张卡抽走"。
                    val forward = depthOf(targetState) >= depthOf(initialState)
                    // ⚠️ 硬约束（2026-11-22 从 Compose 源码 + 逐帧录屏两头确认）：
                    // AnimatedContent 给**每一个**可见内容套的都是同一个 spec 的
                    // `ZIndexModifierElement(specOnEnter.targetContentZIndex, ...)`，两层拿到同一个值，
                    // 于是永远退回"后放置的在上"= **永远是进来的那页在上层**。
                    // 给 targetContentZIndex 设 0/1/2 或 ±1 都改变不了（真机逐帧验证：246ms 里一个像素没动）。
                    // 所以：**位移只做在"进来那页"上**，退场那页只负责"活满时长 + 不露底色"。
                    val enter: EnterTransition = when (state.navAnimation) {
                        // 进：新页整屏从右滑入；返回：上一页从左侧 1/3 处滑回（当前页原位被覆盖）
                        NavAnimation.Slide ->
                            if (forward) {
                                slideInHorizontally(tween(Motion.Slow, easing = Motion.Decelerate)) { it }
                            } else {
                                slideInHorizontally(tween(Motion.Slow, easing = Motion.Decelerate)) { -it / 3 }
                            }

                        NavAnimation.Fade -> fadeIn(tween(Motion.Base, easing = Motion.Decelerate))
                        NavAnimation.None -> EnterTransition.None
                    }
                    val exit: ExitTransition = when (state.navAnimation) {
                        // 退场那页在上层底下，位移看不见。这里只要求它**别提前被销毁**：
                        // exit = None 时 AnimatedContent 会在十几毫秒内就把旧页移除（实测 12–14ms），
                        // 于是动画期间露出来的是窗口底色。用满时长的位移（退 1/4 / 原地 0）把它留住。
                        NavAnimation.Slide ->
                            if (forward) {
                                slideOutHorizontally(tween(Motion.Slow, easing = Motion.Standard)) { -it / 4 }
                            } else {
                                slideOutHorizontally(tween(Motion.Slow)) { 0 }
                            }

                        NavAnimation.Fade -> fadeOut(tween(Motion.Base))
                        NavAnimation.None -> ExitTransition.None
                    }
                    // using(null) = 关掉尺寸动画。这是上一轮录屏逐帧查出的另一个病根：
                    // AnimatedContent 默认带 SizeTransform，容器尺寸会在旧页→新页之间弹簧动画，
                    // 两页在重建瞬间量出的尺寸不一致时容器从 ~0 弹到满屏，里面的 fillMaxSize 内容
                    // 被压在窄框里重排、超出的被裁掉 —— 看着像"内容从屏幕中央长出来"。
                    // sizeTransform 的 setter 是 internal，只能 using(null)。
                    (enter togetherWith exit).using(null)
                },
                label = "destination",
            ) { target ->
                // 转场自检：页面被移除时打个时间差。
                // ~240ms 后才移除 = 转场真的跑了（那"看不见动画"就只能是叠放次序问题）；
                // 几十毫秒内就移除 = 转场根本没跑（问题在别处，比如状态连改两次）。
                DisposableEffect(target) {
                    onDispose {
                        AppLog.i(
                            "nav",
                            "${labelOf(target)} 页面移除，距上次换页 ${System.currentTimeMillis() - navAt[0]}ms",
                        )
                    }
                }
                when (target) {
                    Destination.Tabs -> TabsRoot(state, viewModel, sessionsList, charactersGrid)
                    Destination.Chat -> {
                        BackHandler { viewModel.closeChat() }
                        ChatScreen(state = state, viewModel = viewModel)
                    }

                    is Destination.Settings -> {
                        BackHandler { viewModel.closeSettingsPage() }
                        SettingsSubPage(state = state, viewModel = viewModel)
                    }

                    is Destination.WorldBookEntries -> {
                        BackHandler { viewModel.closeWorldBook() }
                        WorldBookEntriesScreen(state = state, viewModel = viewModel)
                    }

                    is Destination.Detail -> {
                        BackHandler { viewModel.closeDetail() }
                        CharacterDetailScreen(state = state, viewModel = viewModel)
                    }

                    is Destination.CardConfig -> {
                        BackHandler { viewModel.closeCardConfigPage() }
                        CardConfigPageScreen(state = state, viewModel = viewModel)
                    }

                    Destination.ProfileEdit -> {
                        BackHandler { viewModel.closeProfileEditor() }
                        ProfileEditScreen(state = state, viewModel = viewModel)
                    }
                }
            }
        }
    }
}

/** 悬浮胶囊底栏的高度（不含外留白）。 */
private val BarHeight = 56.dp

/**
 * 三栏根页：**悬浮玻璃底栏** + 各 tab 内容（tab 之间的切换另有自己的动画）。
 *
 * 布局要点：底栏不再是 Scaffold 的 bottomBar（那样内容不会滚到它底下，模糊就无内容可透），
 * 而是浮在内容之上；内容盒标记 `hazeSource` 提供"背后的画面"，胶囊用 `hazeEffect` 取来模糊。
 * 列表底部要留出 [contentBottomPadding]，否则最后一行会被胶囊压住。
 */
@Composable
private fun TabsRoot(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    sessionsList: LazyListState,
    charactersGrid: LazyGridState,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val notice = state.notice
    LaunchedEffect(notice?.id) {
        if (notice != null) {
            snackbarHostState.showSnackbar(notice.text)
            viewModel.consumeNotice(notice.id)
        }
    }

    // 玻璃：内容录两份图层（全尺寸一份显示、1/FrostScale 一份给底栏当"糊"）。
    val glassLayer = rememberGraphicsLayer()
    // 降采样的那一份：底栏放大画回去就是"糊"。整条路不碰 RenderEffect ——
    // 这台机器上 Modifier.blur / Haze 两套都没出过模糊，不再押 GPU 效果（ADR 0006）。
    val frostLayer = rememberGraphicsLayer()
    val contentOrigin = remember { mutableStateOf(Offset.Zero) }
    val contentSize = remember { mutableStateOf(IntSize.Zero) }
    // API 31 以下没有 RenderEffect：那时玻璃档按「轻量」画，连图层都不录（省一遍全屏绘制）。
    val glassOn = state.visualEffects == VisualEffects.Glass && Build.VERSION.SDK_INT >= 31
    val view = LocalView.current
    // 页头是浮层，动作也跟着在这一层：导入的 launcher 只能在这里建。
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importCard(uri)
    }
    // 真机上没有 logcat，玻璃到底有没有跑、坐标系对不对，只能靠这行日志事后判断。
    LaunchedEffect(glassOn) {
        if (!glassOn) return@LaunchedEffect
        AppLog.i(
            "glass",
            "玻璃档：sdk=${Build.VERSION.SDK_INT} 硬件加速=${view.isHardwareAccelerated} " +
                "模糊=${GlassBlur.value}dp",
        )
        repeat(3) { withFrameNanos { } }
        AppLog.i("glass", "坐标：内容=${contentOrigin.value} 内容尺寸=${contentSize.value}")
        // 真机上看不出来到底"没糊"还是"图层是空的"，回读一次数不透明像素。
        runCatching {
            withTimeout(2_000) {
                val base = frostLayer.toImageBitmap().asAndroidBitmap()
                    // toImageBitmap 返回的是 HARDWARE 位图，getPixel 会被拒绝，先落到软件位图。
                    .copy(Bitmap.Config.ARGB_8888, false)!!
                var opaque = 0
                var sampled = 0
                var x = 0
                while (x < base.width) {
                    var y = 0
                    while (y < base.height) {
                        sampled++
                        if ((base.getPixel(x, y) ushr 24) != 0) opaque++
                        y += 8
                    }
                    x += 8
                }
                AppLog.i("glass", "降采样图层：${base.width}x${base.height} 采样=$sampled 不透明=$opaque")
            }
        }.onFailure { AppLog.w("glass", "降采样图层回读失败：${it.message}") }
    }
    // 图层要先铺一层页面底色：背景画在 Scaffold/祖先上，不在内容节点的 drawContent() 里。
    // 不铺的话图层里只有文字和头像（实测 544 个采样里只有 9 个不透明），底栏画上去仍是
    // 「透明磨砂」，背后真实内容照样透出来 —— 看起来就是"没糊"（2026-11-22 就是这个）。
    val pageBackground = MaterialTheme.colorScheme.background
    val newSessionAction: @Composable () -> Unit = {
        CapsuleIcon(icon = Icons.Filled.Add, contentDescription = "新建会话") { viewModel.newSession() }
    }
    val importAction: @Composable () -> Unit = {
        CapsuleIcon(
            icon = Icons.Filled.Add,
            contentDescription = if (state.importing) "导入中" else "导入角色卡",
            enabled = !state.importing,
        ) { importLauncher.launch(arrayOf("image/*")) }
    }
    val headerActions: (@Composable () -> Unit)? = when (state.screen) {
        Screen.Sessions -> newSessionAction
        Screen.Characters -> importAction
        Screen.Settings -> null
    }
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentBottom = BarHeight + Space.s + Space.s + navBar

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 不再给状态栏让位：内容铺满整屏，滚到页头（含状态栏）下面时会被磨砂糊掉，
                    // 状态栏因此和页头连成一片（用户 2026-11-23 要求）。
                    .onGloballyPositioned {
                        contentOrigin.value = it.positionInWindow()
                        contentSize.value = it.size
                    }
                    .drawWithContent {
                        if (glassOn) {
                            // 录一份完整内容，底栏要用它当"背后的画面"。
                            // 必须用 **DrawScope 的 record 扩展**（只传 size 的那个）：它会把本 DrawScope
                            // 的画布临时改指到图层上，`drawContent()` 才画进图层。
                            // 四参数成员版 record(density, layoutDirection, size, block) 只改图层自己的
                            // DrawContext，内容会照旧画到主画布 —— 屏幕看着正常、图层是空的（踩过）。
                            glassLayer.record(size = size.toIntSize()) {
                                drawRect(pageBackground)
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(glassLayer)
                            // 再录一份 1/FrostScale 的小层当磨砂素材（成本只跟小层像素数有关）。
                            recordFrost(frostLayer, glassLayer, size.toIntSize())
                        } else {
                            drawContent()
                        }
                    },
            ) {
                AnimatedContent(
                    targetState = state.screen,
                    // 同上层：钉死整屏 + 关掉 SizeTransform，否则 tab 切换也会有"从 0 长出来"的抽动。
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val forward = screenOrder.indexOf(targetState) > screenOrder.indexOf(initialState)
                        (
                            (
                                fadeIn(tween(Motion.Base, easing = Motion.Decelerate)) +
                                    slideInHorizontally(tween(Motion.Base, easing = Motion.Decelerate)) { w ->
                                        if (forward) w / 8 else -w / 8
                                    }
                                ) togetherWith (
                                fadeOut(tween(Motion.Fast)) +
                                    slideOutHorizontally(tween(Motion.Fast)) { w ->
                                        if (forward) -w / 8 else w / 8
                                    }
                                )
                            ).using(null)
                    },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.Sessions -> SessionsScreen(state, viewModel, sessionsList, contentBottom)
                        Screen.Characters ->
                            CharactersScreen(state, viewModel, charactersGrid, contentBottom)

                        Screen.Settings -> SettingsScreen(state, viewModel)
                    }
                }
            }

            // 页头浮层：画在录制之外，否则玻璃胶囊会采样到上一帧的自己（无限套娃）。
            PageHeader(
                title = when (state.screen) {
                    Screen.Sessions -> "会话"
                    Screen.Characters -> "角色卡"
                    Screen.Settings -> "设置"
                },
                glass = glassOn,
                frost = frostLayer,
                contentOrigin = { contentOrigin.value },
                modifier = Modifier.align(Alignment.TopCenter),
                actions = headerActions,
            )

            TavernBottomBar(
                current = state.screen,
                onSelect = viewModel::showScreen,
                glass = glassOn,
                frost = frostLayer,
                contentOrigin = { contentOrigin.value },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** tab 的左右顺序，用于决定切换动画的方向。 */
private val screenOrder = listOf(Screen.Sessions, Screen.Characters, Screen.Settings)

/** 底栏的三项。 */
private data class BarItem(val screen: Screen, val icon: ImageVector, val label: String)

private val barItems = listOf(
    BarItem(Screen.Sessions, Icons.AutoMirrored.Filled.List, "会话"),
    BarItem(Screen.Characters, Icons.Filled.Person, "角色"),
    BarItem(Screen.Settings, Icons.Filled.Settings, "设置"),
)

/**
 * 悬浮底栏：**玻璃胶囊 + 56dp**。
 *
 * 形状、磨砂、描边都在 [GlassSurface] 里；这里只管三项、选中态和"离屏幕边缘多远"。
 * 玻璃档不挂阴影：elevation 阴影画在内容后面，配半透明底会透出亮度台阶（踩过）。
 */
@Composable
private fun TavernBottomBar(
    current: Screen,
    onSelect: (Screen) -> Unit,
    glass: Boolean,
    frost: GraphicsLayer,
    contentOrigin: () -> Offset,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Radius.pill)

    // 外壳只负责"离屏幕边缘多远"和阴影；胶囊本体在里面那层，坐标才准。
    Box(
        modifier = modifier
            .padding(horizontal = Space.l, vertical = Space.s)
            .navigationBarsPadding()
            .then(if (glass) Modifier else Modifier.shadow(elevation = 10.dp, shape = shape, clip = false)),
    ) {
        GlassSurface(
            frost = frost,
            contentOrigin = contentOrigin,
            glass = glass,
            shape = shape,
            modifier = Modifier.fillMaxWidth().height(BarHeight),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                barItems.forEach { item ->
                    val selected = current == item.screen
                    val tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else Color.Transparent,
                            )
                            .clickable { onSelect(item.screen) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = tint,
                        )
                    }
                }
            }
        }
    }
}
