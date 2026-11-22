package app.tavern

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
}

private fun depthOf(destination: Destination): Int = when (destination) {
    Destination.Tabs -> 0
    is Destination.Settings -> 1
    is Destination.Detail -> 1
    Destination.Chat -> 2
    is Destination.CardConfig -> 2
}

/** 日志里的目标名：带页面枚举名，不带卡 id（id 排障没用，还占版面）。 */
private fun labelOf(destination: Destination): String = when (destination) {
    Destination.Tabs -> "tabs"
    Destination.Chat -> "chat"
    is Destination.Settings -> "settings/${destination.page.name}"
    is Destination.Detail -> "detail"
    is Destination.CardConfig -> "card-config/${destination.page.name}"
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

            val destination: Destination = when {
                state.chatOpen -> Destination.Chat
                detailId != null && cardConfig != null -> Destination.CardConfig(detailId, cardConfig)
                detailId != null -> Destination.Detail(detailId)
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
            LaunchedEffect(destination) {
                val depth = depthOf(destination)
                val previous = lastDepth[0]
                val direction = when {
                    previous < 0 -> "init"
                    depth > previous -> "push"
                    depth < previous -> "pop"
                    else -> "switch"
                }
                AppLog.i(
                    "nav",
                    "${labelOf(destination)} depth=$depth $direction anim=${state.navAnimation.name}",
                )
                lastDepth[0] = depth
            }

            AnimatedContent(
                targetState = destination,
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
                    val enter: EnterTransition = when (state.navAnimation) {
                        // 标准：进去时只有新页在动（上一级不动，被覆盖）
                        NavAnimation.Slide ->
                            if (forward) {
                                slideInHorizontally(tween(Motion.Slow, easing = Motion.Decelerate)) { it }
                            } else {
                                EnterTransition.None
                            }

                        // 层叠：两层同时动
                        NavAnimation.Stack ->
                            if (forward) {
                                slideInHorizontally(tween(Motion.Slow, easing = Motion.Decelerate)) { it }
                            } else {
                                slideInHorizontally(tween(Motion.Slow, easing = Motion.Decelerate)) { -it / 4 }
                            }

                        NavAnimation.Fade -> fadeIn(tween(Motion.Base, easing = Motion.Decelerate))
                        NavAnimation.None -> EnterTransition.None
                    }
                    val exit: ExitTransition = when (state.navAnimation) {
                        // 标准：返回时只有当前页在动（向右滑走，露出下面那页）
                        NavAnimation.Slide ->
                            if (forward) {
                                ExitTransition.None
                            } else {
                                slideOutHorizontally(tween(Motion.Slow, easing = Motion.Standard)) { it }
                            }

                        NavAnimation.Stack ->
                            if (forward) {
                                slideOutHorizontally(tween(Motion.Slow, easing = Motion.Standard)) { -it / 4 }
                            } else {
                                slideOutHorizontally(tween(Motion.Slow, easing = Motion.Standard)) { it }
                            }

                        NavAnimation.Fade -> fadeOut(tween(Motion.Base))
                        NavAnimation.None -> ExitTransition.None
                    }
                    // 页面切换不叠 fade（位移未走完就透明 → 露窗口底色，像闪一下）。
                    // zIndex 按深度给：返回时当前页压在上一级之上。
                    (enter togetherWith exit).apply {
                        targetContentZIndex = depthOf(targetState).toFloat()
                    }
                },
                label = "destination",
            ) { target ->
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

                    is Destination.Detail -> {
                        BackHandler { viewModel.closeDetail() }
                        CharacterDetailScreen(state = state, viewModel = viewModel)
                    }

                    is Destination.CardConfig -> {
                        BackHandler { viewModel.closeCardConfigPage() }
                        CardConfigPageScreen(state = state, viewModel = viewModel)
                    }
                }
            }
        }
    }
}

/** 三栏根页：底部导航 + 各 tab 内容（tab 之间的切换另有自己的动画）。 */
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { TavernBottomBar(current = state.screen, onSelect = viewModel::showScreen) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = state.screen,
                transitionSpec = {
                    val forward = screenOrder.indexOf(targetState) > screenOrder.indexOf(initialState)
                    (
                        fadeIn(tween(Motion.Base, easing = Motion.Decelerate)) +
                            slideInHorizontally(tween(Motion.Base, easing = Motion.Decelerate)) { w ->
                                if (forward) w / 8 else -w / 8
                            }
                        ) togetherWith (
                        fadeOut(tween(Motion.Fast)) +
                            slideOutHorizontally(tween(Motion.Fast)) { w -> if (forward) -w / 8 else w / 8 }
                        )
                },
                label = "screen",
            ) { screen ->
                when (screen) {
                    Screen.Sessions -> SessionsScreen(state, viewModel, sessionsList)
                    Screen.Characters -> CharactersScreen(state, viewModel, charactersGrid)
                    Screen.Settings -> SettingsScreen(state, viewModel)
                }
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
 * 自绘底栏，**56dp**。
 *
 * 为什么不用 Material3 的 `NavigationBar`：它固定 80dp 并带胶囊指示器，压不高（标签会被截）。
 * C 方向要的是紧凑、无胶囊、选中态只靠主色区分。
 */
@Composable
private fun TavernBottomBar(current: Screen, onSelect: (Screen) -> Unit) {
    val palette = LocalTavernPalette.current

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = palette.divider)
            Row(modifier = Modifier.fillMaxWidth().height(56.dp)) {
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
