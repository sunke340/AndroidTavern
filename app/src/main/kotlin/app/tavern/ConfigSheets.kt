package app.tavern

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 三级配置的选择界面。
 *
 * 用户 2026-11-25 定过两件事：
 * 1. 会话配置页 / 角色卡配置页里的行**跳到整页去选**（像设置页那样），不再弹底部抽屉；
 * 2. 聊天页左下角那个快速换模型的抽屉保留，但列表要和"设置 → 模型"同一套（带勾选框），
 *    而且**选中后不自动收起**。
 *
 * 所以这个文件现在只留：
 * - [ModelPickerRows]：模型列表（页面与抽屉共用）；
 * - [showModelSheet]：聊天页的快速换模型抽屉（顶部思考强度滑块 + 模型列表）；
 * - 两个"解析后的生效集合"小工具（页面上的"N 本 / N 条"用）。
 */

/**
 * 模型列表（与设置页"模型"同一套行）：勾选圈 + 名字 + 模型 ID。
 *
 * [scope] 决定勾的是哪一层：会话级 / 卡级。点一行只改配置、**不关页面/抽屉**。
 */
@Composable
internal fun ModelPickerRows(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    scope: ConfigScope,
    modifier: Modifier = Modifier,
) {
    val thisLevel = when (scope) {
        ConfigScope.Session -> state.sessionOverride.profileId
        ConfigScope.Card -> state.cardOverride.profileId
    }
    val effectiveId = state.effectiveProfileId
    // 这一层没设 = 跟随上级（勾在「跟随上级」那一行）
    val followName = when (scope) {
        // 会话"跟随上级"跟到卡级（卡级没设就是全局默认档案）
        ConfigScope.Session -> state.cardOverride.profileId
            ?.let { id -> state.profiles.firstOrNull { it.id == id }?.name }
            ?: state.profiles.firstOrNull { it.isDefault }?.name

        ConfigScope.Card -> state.profiles.firstOrNull { it.isDefault }?.name
    }

    if (state.profiles.isEmpty()) {
        Text(
            "还没有模型档案（设置 → 模型）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    // 「跟随上级」**常驻**（用户 2026-11-25 第 4 条）：以前只在"这一层已经有覆盖"时才出现，
    // 于是选中第一个模型后它突然插到列表最上面，整列往下挪一格 —— 手正指着的那一行就换人了。
    // 常驻之后行数不变，点哪行就是哪行。
    val following = thisLevel == null
    ListGroup(count = state.profiles.size + 1, modifier = modifier) { index, _ ->
        if (index == 0) {
            ListRow(
                title = "跟随上级",
                subtitle = followName,
                leading = { ActiveMark(following) },
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.clearSessionProfile()
                        ConfigScope.Card -> viewModel.useCardProfile(null)
                    }
                },
            )
        } else {
            val profile = state.profiles[index - 1]
            ListRow(
                title = profile.name,
                subtitle = buildString {
                    append(profile.model)
                    profile.reasoningEffort?.takeIf { it.isNotBlank() }?.let {
                        append(" · 思考 ").append(it)
                    }
                },
                leading = { ActiveMark(!following && profile.id == effectiveId) },
                onClick = {
                    when (scope) {
                        ConfigScope.Session -> viewModel.useSessionProfile(profile.id)
                        ConfigScope.Card -> viewModel.useCardProfile(profile.id)
                    }
                },
            )
        }
    }
}

/**
 * 聊天页左下角模型钮打开的抽屉：顶部思考强度滑块 + 模型列表。
 *
 * 为什么还留抽屉：这是"边说边换"的快速入口，按一下就到；真要逐项配置去「会话配置」页。
 * 条目点完**不收抽屉**（用户 2026-11-25 第 3 条），勾选圈原地挪过去，方便连着比较。
 */
internal fun showModelSheet(sheet: SheetController, viewModel: ChatViewModel) {
    sheet.show(
        title = "模型",
        actions = emptyList(),
        extra = {
            // 抽屉的 spec 是打开那一刻的快照：这里自己订阅读最新状态，勾选与滑块才跟手
            val live by viewModel.state.collectAsStateWithLifecycle()
            Column {
                ThinkingEffortSlider(
                    current = live.sessionOverride.reasoningEffort,
                    effective = live.effectiveReasoningEffort,
                    onSelect = viewModel::useSessionReasoning,
                )
                Spacer(Modifier.height(Space.m))
                ModelPickerRows(state = live, viewModel = viewModel, scope = ConfigScope.Session)
            }
        },
    )
}

/** 解析后的世界书集合（用于显示"参与几本"）。 */
internal fun effectiveBookNames(state: ChatViewModel.UiState): Set<String> =
    state.effectiveWorldBookNames ?: state.worldBooks.filter { it.enabled }.map { it.name }.toSet()

/** 解析后的正则集合。 */
internal fun effectiveRegexNames(state: ChatViewModel.UiState): Set<String> =
    state.effectiveRegexIds ?: state.regexScripts.map { it.name }.filter { it.isNotBlank() }.toSet()

/** 会话里是否已经有用户消息（开场白切换只看这个）。 */
internal fun hasUserMessage(state: ChatViewModel.UiState): Boolean =
    state.transcript.any { it.role == app.tavern.core.model.ChatRole.User }
