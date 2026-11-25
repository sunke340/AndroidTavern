package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 会话配置（三级页）：**这一条会话**单独用什么。
 *
 * 三级继承：会话（这里）→ 角色卡（详情页「配置」）→ 全局（设置里那几页）。
 * 每一行都显示"生效值 · 是谁定的"，不用点进去猜。
 *
 * 思考强度是**滑块**（用户 2026-11-25 第 4 条），不再藏在模型抽屉里当一列选项；
 * 其余四项仍是"点一行 → 抽屉里选"。
 */
@Composable
fun SessionConfigScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    // 控制器必须在这里建再传进骨架：本页面在 GlassDetailScaffold **之前**，
    // 直接读 CompositionLocal 会拿到没有宿主的默认实例，点了什么都不弹
    // （2026-11-25 真机清单"会话配置页的按钮点不进去"就是这一条）。
    val sheet = remember { SheetController() }
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
        sheetController = sheet,
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
                        showModelSheet(sheet, state, viewModel, ConfigScope.Session)
                    }

                    1 -> SettingLinkRow("世界书", "参与 ${books.size} 本 · $booksSrc") {
                        showWorldBookSheet(sheet, state, viewModel, ConfigScope.Session, books)
                    }

                    2 -> SettingLinkRow("正则", "参与 ${regex.size} 条 · $regexSrc") {
                        showRegexSheet(sheet, state, viewModel, ConfigScope.Session, regex)
                    }

                    else -> SettingLinkRow("预设", "$preset · $presetSrc") {
                        showPresetSheet(sheet, state, viewModel, ConfigScope.Session)
                    }
                }
            }

            ThinkingEffortSlider(
                current = state.sessionOverride.reasoningEffort,
                effective = state.effectiveReasoningEffort,
                onSelect = viewModel::useSessionReasoning,
            )
        }
    }
}
