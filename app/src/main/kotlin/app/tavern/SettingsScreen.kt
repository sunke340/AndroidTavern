package app.tavern

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 设置一级页：只放"去哪儿"，不放控件。
 *
 * 每行右边显示**当前生效值**，这样不用点进去也知道现在用的是哪个模型、什么主题。
 */
@Composable
fun SettingsScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    // 一级行只说"现在用哪条档案"，细节进二级页看
    val activeProfile = state.profiles.firstOrNull { it.id == state.activeProfileId }
    val modelSummary = activeProfile?.let { "${it.name} · ${it.model}" }
        ?: state.model.ifBlank { "未配置" }
    val samplingSummary = "温度 ${state.temperatureText.ifBlank { "—" }}" +
        " · 最大输出 ${state.maxTokensText.takeIf { it != "0" } ?: "不限"}"
    val appearanceSummary = "${state.themePreset.label} · ${state.themeMode.label} · " +
        if (state.messageStyle == MessageStyle.Bubble) "气泡" else "整行卡片"
    // 页头一直铺到状态栏后面，内容要让出"状态栏 + 页头"的高度
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bookCards = state.cards.count { it.bookEntryCount > 0 }
    val enabledBooks = state.worldBooks.count { it.enabled }
    val worldBookSummary = "全局 ${state.worldBooks.size} 本（启用 $enabledBooks）· $bookCards 张卡带内嵌书"

    // 一级页也要能"拉过头再弹回来"：橡皮筋挂在 verticalScroll 外层
    val overscroll = rememberRubberOverscroll()
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .rubberOverscroll(overscroll)
            .verticalScroll(scroll)
            .padding(top = PageHeaderHeight + Space.s + statusBar),
    ) {
        SettingLinkRow("模型", modelSummary) { viewModel.openSettingsPage(SettingsPage.Model) }
        SettingLinkRow("采样", samplingSummary) { viewModel.openSettingsPage(SettingsPage.Sampling) }
        SettingLinkRow("外观", appearanceSummary) { viewModel.openSettingsPage(SettingsPage.Appearance) }
        SettingLinkRow("世界书", worldBookSummary) {
            viewModel.openSettingsPage(SettingsPage.WorldBook)
        }
        SettingLinkRow("正则", "${state.regexScripts.count { !it.disabled }} / ${state.regexScripts.size} 条启用") {
            viewModel.openSettingsPage(SettingsPage.Regex)
        }
        SettingLinkRow("关于", "构建与版本信息") {
            viewModel.openSettingsPage(SettingsPage.About)
        }
    }
}
