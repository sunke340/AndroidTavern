package app.tavern

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 顶层结构：三栏（会话 / 角色 / 设置），聊天是叠在上面的二级页。
 *
 * 为什么聊天不占一个 tab：底部栏放的是**平级目的地**，聊天是从会话进去的详情，
 * 带返回栈才符合安卓返回键。世界书同理不进底部栏——它是跟着卡和会话走的编辑面，
 * 入口在「角色详情 / 聊天 ⋮ / 设置」三处（P1 再做）。
 */
@Composable
fun MainScreen(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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

    Scaffold(
        // 三栏页面的状态栏/导航栏留白由 Scaffold 的 contentWindowInsets 负责；
        // imePadding 让键盘弹出时底部导航和内容一起上移，而不是把输入框盖住
        modifier = Modifier.fillMaxSize().imePadding(),
        bottomBar = {
            NavigationBar {
                // 用文字当图标：不引 material-icons-extended，少一个依赖少一次 CI 风险
                NavigationBarItem(
                    selected = state.screen == Screen.Sessions,
                    onClick = { viewModel.showScreen(Screen.Sessions) },
                    icon = { Text("💬") },
                    label = { Text("会话") },
                )
                NavigationBarItem(
                    selected = state.screen == Screen.Characters,
                    onClick = { viewModel.showScreen(Screen.Characters) },
                    icon = { Text("🎭") },
                    label = { Text("角色") },
                )
                NavigationBarItem(
                    selected = state.screen == Screen.Settings,
                    onClick = { viewModel.showScreen(Screen.Settings) },
                    icon = { Text("⚙️") },
                    label = { Text("设置") },
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
