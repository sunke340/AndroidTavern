package app.tavern

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
    val bookCards = state.cards.count { it.bookEntryCount > 0 }
    val worldBookSummary = if (bookCards == 0) "没有卡带内嵌世界书" else "$bookCards 张卡带内嵌世界书"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = PageHeaderHeight + Space.s),
    ) {
        SettingLinkRow("模型", modelSummary) { viewModel.openSettingsPage(SettingsPage.Model) }
        SettingLinkRow("采样", samplingSummary) { viewModel.openSettingsPage(SettingsPage.Sampling) }
        SettingLinkRow("外观", appearanceSummary) { viewModel.openSettingsPage(SettingsPage.Appearance) }
        SettingLinkRow("世界书", worldBookSummary) {
            viewModel.openSettingsPage(SettingsPage.WorldBook)
        }
        SettingLinkRow("关于", "构建与版本信息") {
            viewModel.openSettingsPage(SettingsPage.About)
        }
    }
}
