package app.tavern

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 关键：让窗口不再随键盘缩放，状态栏/导航栏/键盘全部交给 insets 处理。
        // 不做这一步时，Android 14 及以下会"系统缩放窗口 + Compose 给 imePadding 做动画"双份位移，
        // 表现就是点输入框时画面先抽一下再落回原位。
        enableEdgeToEdge()
        setContent {
            // 主题在 MainScreen 里套（要靠 UiState 的 themePreset/themeMode）
            MainScreen()
        }
    }
}
