package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 会话配置（二级页）：**这一条会话**单独用什么。
 *
 * 三级继承：会话（这里）→ 角色卡（详情页「配置」）→ 全局（设置里那几页）。
 * 每一行都显示"生效值 · 是谁定的"，不用点进去猜。
 */
@Composable
fun SessionConfigScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val sheet = LocalSheetController.current
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
    val effort = when (state.effectiveReasoningEffort) {
        null -> "跟随档案"
        "off" -> "关"
        "low" -> "低"
        "medium" -> "中"
        "high" -> "高"
        else -> state.effectiveReasoningEffort.orEmpty()
    }
    val effortSrc = src(
        sessionSet = state.sessionOverride.reasoningEffort != null,
        cardSet = state.cardOverride.reasoningEffort != null,
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
        subtitle = "会话 → 角色卡 → 全局",
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
            ListGroup(count = 5) { index, _ ->
                when (index) {
                    0 -> SettingLinkRow("模型", "$profileName · $modelSrc") {
                        showModelSheet(sheet, state, viewModel, ConfigScope.Session)
                    }

                    1 -> SettingLinkRow("思考强度", "$effort · $effortSrc") {
                        showModelSheet(sheet, state, viewModel, ConfigScope.Session)
                    }

                    2 -> SettingLinkRow("世界书", "参与 ${books.size} 本 · $booksSrc") {
                        showWorldBookSheet(sheet, state, viewModel, ConfigScope.Session, books)
                    }

                    3 -> SettingLinkRow("正则", "参与 ${regex.size} 条 · $regexSrc") {
                        showRegexSheet(sheet, state, viewModel, ConfigScope.Session, regex)
                    }

                    else -> SettingLinkRow("预设", "$preset · $presetSrc") {
                        showPresetSheet(sheet, state, viewModel, ConfigScope.Session)
                    }
                }
            }

            Text(
                "没在这一层选的项一律跟随角色卡，再跟随全局。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
