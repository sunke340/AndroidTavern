package app.tavern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 开场白翻页的两枚箭头。
 *
 * 为什么是箭头而不是"选择某条"的列表（用户 2026-11-25 定的形态）：开场白是要**读**的东西，
 * 一条条翻着看、看到了就停，比在一列标题里挑更接近真实的动作；而且原版 ST 里换开场白
 * 本来就是"对第一条消息 swipe"，箭头就是那个手势的按钮版，以后接 P0-6 的 swipe 是同一条路。
 *
 * 用文字箭头而不是图标：`material-icons-core` 里没有左右箭头（在 extended 包里，
 * 为两个箭头引 10MB 不值得）。
 */
@Composable
internal fun GreetingChevron(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            },
        )
    }
}

/**
 * 聊天页：贴在开场白气泡**上沿**的那一条「‹ 2/4 ›」。
 *
 * 气泡是"可以被换掉的那个东西"，控件就贴在它身上 —— 不用去页头找按钮再回头看气泡。
 * 只在"会话还没有用户消息、且这张卡有不止一条开场白"时出现（用户 2026-11-25 的 B 策略）。
 */
@Composable
internal fun GreetingSwitchBar(current: Int, total: Int, onSelect: (Int) -> Unit) {
    val palette = LocalTavernPalette.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(1.dp, palette.cardBorder, RoundedCornerShape(Radius.pill))
            .padding(horizontal = Space.xs, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GreetingChevron("‹", enabled = current > 0) { onSelect((current - 1).coerceAtLeast(0)) }
        Text(
            text = "${current + 1}/$total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.xs),
        )
        GreetingChevron("›", enabled = current < total - 1) {
            onSelect((current + 1).coerceAtMost(total - 1))
        }
    }
}

/** 取"第一条消息对应第几条开场白"：对不上（用户改过 / 导入的旧会话）返回 -1。 */
internal fun greetingIndexOf(greetings: List<String>, firstMessage: String?): Int {
    val text = firstMessage?.trim().orEmpty()
    if (text.isEmpty() || greetings.isEmpty()) return -1
    return greetings.indexOfFirst { it.trim() == text }
}
