package app.tavern

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 占位气泡的两种语义：新回复 / 重新生成（后者要告诉用户"旧的那条正在被替换"）。 */
internal enum class PendingKind { Generating, Regenerating }

/**
 * 占位气泡里那一行：`生成中` + 三个呼吸点 / `重新生成中` + 三个呼吸点（用户 2026-11-26 定）。
 *
 * 为什么要有它：点发送到首 token 之间，列表里原本**什么都不出现**，观感是"点了没反应"，
 * 而且新气泡要在首 token 那一刻才插进列表（反排列表会把它锚到视口外 —— "AI 气泡掉到输入框下面"）。
 * 占位气泡把"气泡出现"提前到点下去的那一帧，首 token 只改正文，不再动列表结构。
 *
 * 为什么是呼吸点而不是静止文案：这是在告诉用户"还在等模型，没死"，属于 Motion 三条规矩里的
 * "帮助理解状态变化的动画"，不是装饰。减动效时退成静止三点（[rememberReduceMotion]）。
 */
@Composable
internal fun PendingLine(kind: PendingKind, fontSizeSp: Float, contentAlpha: Float = 1f) {
    // 颜色是写死的 `onSurfaceVariant`（不走 LocalContentColor），所以外层那句
    // "把 contentColor 乘一个 alpha"对它无效 —— 得自己乘。重新生成收缩结束时靠它渐显。
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val color = base.copy(alpha = base.alpha * contentAlpha)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (kind == PendingKind.Regenerating) "重新生成中" else "生成中",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSizeSp.sp),
            color = color,
        )
        Spacer(Modifier.width(6.dp))
        TypingDots(color)
    }
}

/**
 * 三个呼吸点：透明度 0.25 → 1 交错呼吸（单点半周期 450ms，点间错开 120ms）。
 *
 * 只改绘制（alpha），**不动布局**：所以它不会把气泡高度撑来撑去，
 * 也不会干扰串流那条"布局自然增长 + 绘制桥接"的规矩。
 */
@Composable
internal fun TypingDots(color: Color, modifier: Modifier = Modifier) {
    // 系统「移除动画」：不呼吸，静态三点（不创建 infiniteTransition，省得白转帧）
    if (rememberReduceMotion()) {
        DotsRow(color, listOf(0.5f, 0.5f, 0.5f), modifier)
        return
    }
    val transition = rememberInfiniteTransition(label = "typing-dots")
    val alphas = (0 until 3).map { index ->
        val alpha by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 450,
                    delayMillis = index * 120,
                    easing = Motion.Standard,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dot-$index",
        )
        alpha
    }
    DotsRow(color, alphas, modifier)
}

@Composable
private fun DotsRow(color: Color, alphas: List<Float>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        alphas.forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .background(color, CircleShape),
            )
        }
    }
}
