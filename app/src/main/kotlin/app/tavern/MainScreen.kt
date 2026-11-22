package app.tavern

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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

            AnimatedContent(
                targetState = destination,
                transitionSpec = {
                    val forward = depthOf(targetState) >= depthOf(initialState)
                    if (forward) {
                        // 进二级页：新页整屏从右边推入，旧页往左退 1/4（卡片堆的手感）
                        (
                            slideInHorizontally(tween(Motion.Slow, easing = Motion.Decelerate)) { it } +
                                fadeIn(tween(Motion.Base))
                            ) togetherWith (
                            slideOutHorizontally(tween(Motion.Slow)) { -it / 4 } +
                                fadeOut(tween(Motion.Base))
                            )
                    } else {
                        // 返回：当前页整屏往右退场，露出上一级（从左侧回来 1/4）
                        (
                            slideInHorizontally(tween(Motion.Slow, easing = Motion.Decelerate)) { -it / 4 } +
                                fadeIn(tween(Motion.Base))
                            ) togetherWith (
                            slideOutHorizontally(tween(Motion.Slow)) { it } +
                                fadeOut(tween(Motion.Base))
                            )
                    }
                },
                label = "destination",
            ) { target ->
                when (target) {
                    Destination.Tabs -> TabsRoot(state, viewModel)
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
private fun TabsRoot(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
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
                    Screen.Sessions -> SessionsScreen(state, viewModel)
                    Screen.Characters -> CharactersScreen(state, viewModel)
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
