package app.tavern

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import app.tavern.data.log.AppLog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 关键：让窗口不再随键盘缩放，状态栏/导航栏/键盘全部交给 insets 处理。
        // 不做这一步时，Android 14 及以下会"系统缩放窗口 + Compose 给 imePadding 做动画"双份位移，
        // 表现就是点输入框时画面先抽一下再落回原位。
        enableEdgeToEdge()
        syncWindowBackground()
        // 分享进来的卡：onCreate（冷启动）与 onNewIntent（已在运行时）两条路都要接
        SharedIntake.fromIntent(intent)
        setContent {
            // 主题在 MainScreen 里套（要靠 UiState 的 themePreset/themeMode）
            MainScreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SharedIntake.fromIntent(intent)
    }

    /**
     * 把**系统窗口的底色**对齐到当前主题的 `background`。
     *
     * 为什么要管：Compose 第一帧之前，屏幕上是系统按主题画的窗口（Android 12+ 还有 splash），
     * XML 里只能钉一份颜色（`@color/boot_background` = 默认墨紫主题的底色）。
     * 用户换了主题色板或深色模式时，那一下就是"窗口底色 → 应用底色"跳一档
     * （2026-11-24 用户报：先白色铺满 → 淡紫 → 主页渐入；白色就是旧的 `#FFFBFE`）。
     *
     * 这里只读偏好（几十微秒、不碰 Keystore），不刷新 UI。
     */
    private fun syncWindowBackground() {
        runCatching {
            val look = AppGraph.settings(this).uiAppearance()
            val preset = ThemePreset.valueOf(look.themePreset)
            val dark = when (ThemeMode.valueOf(look.themeMode)) {
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                ThemeMode.Follow ->
                    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
            }
            val background = (if (dark) preset.dark else preset.light).background
            window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
        }.onFailure { AppLog.w("startup", "窗口底色对齐失败：${it.message}") }
    }
}
