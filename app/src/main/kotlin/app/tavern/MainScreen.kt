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
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
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
 * 顶层结构：三栏（会话 / 角色 / 设置），聊天是叠在上面的二级页。
 *
 * 为什么聊天不占一个 tab：底部栏放的是**平级目的地**，聊天是从会话进去的详情，
 * 带返回栈才符合安卓返回键。世界书同理不进底部栏——它是跟着卡和会话走的编辑面。
 *
 * 主题在最外层套：换主题色/深浅时整棵树重绘，二级页也一起变。
 */
@Composable
fun MainScreen(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    TavernTheme(mode = state.themeMode, preset = state.themePreset) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            MainContent(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun MainContent(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    if (state.chatOpen) {
        // 聊天页全屏：底部导航隐藏，返回键先退聊天而不是退出 App
        BackHandler { viewModel.closeChat() }
        ChatScreen(state = state, viewModel = viewModel)
        return
    }

    if (state.detailCardId != null) {
        // 角色详情也是全屏二级页（从角色网格点进来）
        BackHandler { viewModel.closeDetail() }
        CharacterDetailScreen(state = state, viewModel = viewModel)
        return
    }

    // 导入/删除这类一次性结果用 Snackbar 报，不占页头（页头高度必须固定）
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
        bottomBar = {
            TavernBottomBar(current = state.screen, onSelect = viewModel::showScreen)
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // tab 切换：按"左中右"的顺序做横向位移（新页从对应方向进来，旧页往反方向走）。
            // 三个 tab 在底栏是水平排列的，横移才符合方向直觉。
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
 * C 方向要的是紧凑、无胶囊、选中态只靠主色区分。代价是失去它自带的指示器动画，
 * 下一批做动效时自己补一个淡入。
 *
 * insets：背景要铺到系统导航栏底下（否则会露出一条缝），内容用 `navigationBarsPadding()` 让开。
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
