package app.tavern

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
            val palette = LocalTavernPalette.current
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationItem(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = "会话",
                    selected = state.screen == Screen.Sessions,
                    onClick = { viewModel.showScreen(Screen.Sessions) },
                )
                NavigationItem(
                    icon = Icons.Filled.Person,
                    label = "角色",
                    selected = state.screen == Screen.Characters,
                    onClick = { viewModel.showScreen(Screen.Characters) },
                )
                NavigationItem(
                    icon = Icons.Filled.Settings,
                    label = "设置",
                    selected = state.screen == Screen.Settings,
                    onClick = { viewModel.showScreen(Screen.Settings) },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.screen) {
                Screen.Sessions -> SessionsScreen(state, viewModel)
                Screen.Characters -> CharactersScreen(state, viewModel)
                Screen.Settings -> SettingsScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavigationItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}
