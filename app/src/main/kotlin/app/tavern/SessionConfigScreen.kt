package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 会话配置（三级页）：**这一条会话**单独用什么。
 *
 * 三级继承：会话（这里）→ 角色卡（详情页「配置」）→ 全局（设置里那几页）。
 * 每行显示"生效值 · 是谁定的"；点进去是**整页选择**（用户 2026-11-25：不要再弹抽屉）。
 * 思考强度在「模型」页顶部，不再单独占一行。
 */
@Composable
fun SessionConfigScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val glass = state.visualEffects.effective() == VisualEffects.Glass

    fun src(sessionSet: Boolean, cardSet: Boolean): String = when {
        sessionSet -> "会话"
        cardSet -> "角色卡"
        else -> "全局"
    }

    val profileName = state.profiles.firstOrNull { it.id == state.effectiveProfileId }?.name ?: "未配置"
    val modelSrc = src(
        sessionSet = state.sessionOverride.profileId != null,
        cardSet = state.cardOverride.profileId != null,
    )
    val books = effectiveBookNames(state)
    val booksSrc = src(
        sessionSet = state.sessionOverride.worldBookNames != null,
        cardSet = state.cardOverride.worldBookNames != null,
    )
    val regex = effectiveRegexNames(state)
    val regexSrc = src(
        sessionSet = state.sessionOverride.regexIds != null,
        cardSet = state.cardOverride.regexIds != null,
    )
    val preset = state.effectivePresetName ?: "内置默认"
    val presetSrc = src(
        sessionSet = state.sessionOverride.presetName != null,
        cardSet = state.cardOverride.presetName != null,
    )

    GlassDetailScaffold(
        title = "会话配置",
        onBack = viewModel::closeSessionConfig,
        glass = glass,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .rubberOverscroll(rememberRubberOverscroll())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l, vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            ListGroup(count = 4) { index, _ ->
                when (index) {
                    0 -> SettingLinkRow("模型", "$profileName · $modelSrc") {
                        viewModel.openConfigPicker(ConfigScope.Session, ConfigKind.Model)
                    }

                    1 -> SettingLinkRow("世界书", "参与 ${books.size} 本 · $booksSrc") {
                        viewModel.openConfigPicker(ConfigScope.Session, ConfigKind.WorldBook)
                    }

                    2 -> SettingLinkRow("正则", "参与 ${regex.size} 条 · $regexSrc") {
                        viewModel.openConfigPicker(ConfigScope.Session, ConfigKind.Regex)
                    }

                    else -> SettingLinkRow("预设", "$preset · $presetSrc") {
                        viewModel.openConfigPicker(ConfigScope.Session, ConfigKind.Preset)
                    }
                }
            }
        }
    }
}
