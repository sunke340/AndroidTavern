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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
 * 导航用一个 [Destination] 状态 + 自写的 [PageStack] 表达，而不是 early return：
 * 后者在换页时是"整棵树直接替换"，没法做进/出动画。
 *
 * 深度决定动画方向：深→更深 从右侧进；变浅 反向退出。
 */
// internal：PageStack.kt 在同一个模块里要引用它（private 会报 "exposes its private-in-file parameter type"）
internal sealed interface Destination {
    data object Tabs : Destination
    data object Chat : Destination
    data class Settings(val page: SettingsPage) : Destination
    data class Detail(val cardId: String) : Destination
    data class CardConfig(val cardId: String, val page: CardConfigPage) : Destination
    /** 设置 → 模型 → 某条档案（三级）。 */
    data object ProfileEdit : Destination
    /** 设置 → 世界书 → 某一本（三级）。 */
    data class WorldBookEntries(val name: String) : Destination
    /** 组件预览（debug only，从 设置 → 关于 进）。 */
    data object Gallery : Destination
    /** 设置 → 预设 → 某一份（三级）：提示词顺序的开关与拖动。 */
    data class PresetEdit(val name: String) : Destination
}

private fun depthOf(destination: Destination): Int = when (destination) {
    Destination.Tabs -> 0
    is Destination.Settings -> 1
    is Destination.Detail -> 1
    Destination.Chat -> 2
    is Destination.CardConfig -> 2
    Destination.ProfileEdit -> 2
    is Destination.WorldBookEntries -> 2
    Destination.Gallery -> 2
    is Destination.PresetEdit -> 2
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
    Destination.Gallery -> "gallery"
    is Destination.PresetEdit -> "preset-edit"
}

@Composable
fun MainScreen(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 系统「移除动画」：位移类转场统一降级成淡入淡出（见 ReducedMotion.kt）
    val reduceMotion = rememberReduceMotion()

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
            val presetEdit = state.presetEditName

            val destination: Destination = when {
                state.chatOpen -> Destination.Chat
                detailId != null && cardConfig != null -> Destination.CardConfig(detailId, cardConfig)
                detailId != null -> Destination.Detail(detailId)
                // 档案编辑页叠在"模型列表"之上：返回时回到列表，所以 settingsPage 不清
                editingProfile -> Destination.ProfileEdit
                // 组件预览叠在"设置→关于"之上：返回落在关于页，所以 settingsPage 不清
                state.galleryOpen -> Destination.Gallery
                // 预设编辑叠在"设置→预设"之上：返回落在预设列表，所以 settingsPage 不清
                presetEdit != null -> Destination.PresetEdit(presetEdit)
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

            // 启动遮罩盖在最上层：首屏列表与头像准备好之前什么都不画（见 StartupCurtain）
            Box(Modifier.fillMaxSize()) {
                // 页面栈转场：自己管叠放次序（不用 AnimatedContent，理由见 PageStack.kt 顶部注释）
                PageStack(
                    target = destination,
                    state = state,
                    depthOf = { depthOf(it) },
                    // 系统「移除动画」：位移换成交叉淡化（保留"页面变了"的提示，去掉"页面在动"）
                    mode = if (reduceMotion) NavAnimation.Fade else state.navAnimation,
                    modifier = Modifier.fillMaxSize(),
                    onRemoved = { dest ->
                        // 转场自检：离场页被真正移除时打个时间差。
                        // ~280ms 后才移除 = 转场真的跑了；几十毫秒内就移除 = 转场根本没跑（问题在状态层）。
                        AppLog.i(
                            "nav",
                            "${labelOf(dest)} 页面移除，距上次换页 ${System.currentTimeMillis() - navAt[0]}ms",
                        )
                    },
                ) { target, snap ->
                    // `snap`：当前页是实时状态，离场页是"它还是当前页时"的快照
                    // （离场页如果读实时状态，会读到已经被清掉的 cardId/session → 渲染空 → 凭空消失）。
                    // BackHandler 只给当前页注册，否则离场那页在 280ms 里也能响应返回键。
                    val live = target == destination
                    when (target) {
                        Destination.Tabs -> TabsRoot(snap, viewModel, sessionsList, charactersGrid)
                        Destination.Chat -> {
                            if (live) BackHandler { viewModel.closeChat() }
                            ChatScreen(state = snap, viewModel = viewModel)
                        }

                        is Destination.Settings -> {
                            if (live) BackHandler { viewModel.closeSettingsPage() }
                            SettingsSubPage(state = snap, viewModel = viewModel)
                        }

                        is Destination.WorldBookEntries -> {
                            if (live) BackHandler { viewModel.closeWorldBook() }
                            WorldBookEntriesScreen(state = snap, viewModel = viewModel)
                        }

                        is Destination.Detail -> {
                            if (live) BackHandler { viewModel.closeDetail() }
                            CharacterDetailScreen(state = snap, viewModel = viewModel)
                        }

                        is Destination.CardConfig -> {
                            if (live) BackHandler { viewModel.closeCardConfigPage() }
                            CardConfigPageScreen(state = snap, viewModel = viewModel)
                        }

                        Destination.ProfileEdit -> {
                            if (live) BackHandler { viewModel.closeProfileEditor() }
                            ProfileEditScreen(state = snap, viewModel = viewModel)
                        }

                        Destination.Gallery -> {
                            if (live) BackHandler { viewModel.closeGallery() }
                            GalleryScreen(state = snap, viewModel = viewModel)
                        }

                        is Destination.PresetEdit -> {
                            if (live) BackHandler { viewModel.closePresetEditor() }
                            PresetEditScreen(state = snap, viewModel = viewModel)
                        }
                    }
                }
                StartupCurtain(booted = state.booted)
            }
        }
    }
}

/**
 * 启动遮罩：**首屏列表（含头像）就绪之前，屏幕上什么都不画**。
 *
 * 之前是导航壳先画出来、列表晚一步落 state，观感是"UI 突然出现，然后列表又补一下"。
 * 现在盖一块主题底色，`booted` 一置真就整块淡出 —— 壳、底栏、列表是同时浮现的。
 */
@Composable
private fun StartupCurtain(booted: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (booted) 0f else 1f,
        animationSpec = tween(Motion.Base, easing = Motion.Decelerate),
        label = "boot-curtain",
    )
    if (alpha > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
                .background(MaterialTheme.colorScheme.background)
                // 遮罩期间下面的界面已经在树里了：把触摸全吃掉，别点到看不见的控件
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                },
        )
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
    // 系统「移除动画」：tab 切换的位移也降级成淡化
    val reduceMotion = rememberReduceMotion()
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
    // 玻璃档要 API 33+（AGSL 液态玻璃）；低于它只开放轻量
    val glassOn = state.visualEffects.effective() == VisualEffects.Glass
    // 动作单的宿主状态：页面通过 CompositionLocal 请求，这里渲染（在录图层之外，能采真磨砂）
    val sheet = remember { SheetController() }
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
            CompositionLocalProvider(LocalSheetController provides sheet) {
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
                        val enter: EnterTransition =
                            if (reduceMotion) {
                                fadeIn(tween(Motion.Base, easing = Motion.Decelerate))
                            } else {
                                fadeIn(tween(Motion.Base, easing = Motion.Decelerate)) +
                                    slideInHorizontally(tween(Motion.Base, easing = Motion.Decelerate)) { w ->
                                        if (forward) w / 8 else -w / 8
                                    }
                            }
                        val exit: ExitTransition =
                            if (reduceMotion) {
                                fadeOut(tween(Motion.Fast))
                            } else {
                                fadeOut(tween(Motion.Fast)) +
                                    slideOutHorizontally(tween(Motion.Fast)) { w ->
                                        if (forward) -w / 8 else w / 8
                                    }
                            }
                        (enter togetherWith exit).using(null)
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
                // 抽屉要弹出来时，底栏先滑走 —— 两块浮层不叠在一起（用户 2026-11-24）
                hidden = sheet.spec != null || state.showCardPicker,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // 选卡弹层（新建会话前必须先选卡）：一级页的浮层里，才有真磨砂
            GlassSheetScaffold(
                visible = state.showCardPicker,
                onDismiss = viewModel::closeCardPicker,
                frost = frostLayer,
                contentOrigin = { contentOrigin.value },
                glass = glassOn,
                enterDelayMillis = SheetEnterDelayMillis,
                title = "选一张卡开始新会话",
            ) {
                CardPickerBody(state = state, viewModel = viewModel)
            }

            // 动作单画在最后 = 压在页头、底栏与选卡弹层之上
            GlassActionSheetHost(
                controller = sheet,
                frost = frostLayer,
                contentOrigin = { contentOrigin.value },
                glass = glassOn,
                enterDelayMillis = SheetEnterDelayMillis,
            )
            }
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
    hidden: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Radius.pill)
    // 抽屉弹出前先让位：往下滑走整条，再回来时错开一个"弹层收起"的时间
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hidePx = with(LocalDensity.current) { (BarHeight + Space.s + navBottom + Space.s).toPx() }
    val slideOff by animateFloatAsState(
        targetValue = if (hidden) hidePx else 0f,
        animationSpec = if (hidden) {
            tween(180, easing = Motion.Standard)
        } else {
            tween(180, delayMillis = 200, easing = Motion.Decelerate)
        },
        label = "bar-hide",
    )

    // 外壳只负责"离屏幕边缘多远"和阴影；胶囊本体在里面那层，坐标才准。
    Box(
        modifier = modifier
            .offset { IntOffset(0, slideOff.roundToInt()) }
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
            // 选中底块只有**一块**，跟着选中项滑过去。
            // 之前是每个 tab 自己画一块 14% 主色：切换时一块消失、另一块出现，看着就是"闪一下"。
            val reduceMotion = rememberReduceMotion()
            var rowWidth by remember { mutableStateOf(0) }
            // 第一次量到真实宽度之前 `cell` 是 0，滑块的目标其实是"最左边"。如果把这一跳
            // 也交给动画，从任何二级页返回（底栏是重新组合的）就会看到滑块从最左边滑过来。
            // 所以首次拿到宽度时 snap，之后才是滑动。
            var pillMeasured by remember { mutableStateOf(false) }
            val cell = rowWidth.toFloat() / barItems.size
            val index = barItems.indexOfFirst { it.screen == current }.coerceAtLeast(0)
            val pillX by animateFloatAsState(
                targetValue = index * cell,
                animationSpec = when {
                    reduceMotion || !pillMeasured -> snap()
                    // 屏幕上移动属于"空间连续"，用 ease-in-out 一类；200ms 跟得上手指又不算慢
                    else -> tween(200, easing = Motion.Standard)
                },
                label = "tab-pill",
            )
            LaunchedEffect(cell) { if (cell > 0f) pillMeasured = true }
            Box(Modifier.fillMaxSize().padding(Space.xs)) {
                if (rowWidth > 0) {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(pillX.roundToInt(), 0) }
                            .width(with(LocalDensity.current) { cell.toDp() })
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(Radius.pill))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxSize().onSizeChanged { rowWidth = it.width },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    barItems.forEach { item ->
                        val selected = current == item.screen
                        val tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        // 点按反馈：很轻的一层主色（按下当帧满色、松开淡回），不是涟漪 ——
                        // 涟漪是方形深色块，会压在滑动中的紫块上（用户 2026-11-24）
                        val press = remember { MutableInteractionSource() }
                        val pressTint = rememberPressTint(press)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(Radius.pill))
                                .background(
                                    if (pressTint > 0f) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.06f * pressTint)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable(
                                    interactionSource = press,
                                    indication = null,
                                ) { onSelect(item.screen) },
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
}
