package app.tavern

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 思考强度的一档：`value = null` 表示"跟随上级"。顺序就是界面从左到右的顺序。 */
internal data class ThinkingOption(val value: String?, val label: String)

internal val ThinkingOptions: List<ThinkingOption> = listOf(
    ThinkingOption(null, "跟随"),
    ThinkingOption("off", "关"),
    ThinkingOption("low", "低"),
    ThinkingOption("medium", "中"),
    ThinkingOption("high", "高"),
)

/** 生效值的文字（页面上的"生效值 · 来源"用）。 */
internal fun reasoningLabel(value: String?): String = when (value) {
    null -> "跟随档案"
    "off" -> "关"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    else -> value
}

/**
 * 思考强度滑块（用户 2026-11-25 第 4 条）：**左右排列 + 一块滑动的选中块**，
 * 和首页底部菜单栏同一种手感；不再是一列抽屉选项。
 *
 * 为什么不用 Switch/Slider 两个控件拼：档位是离散的五个状态（含"跟随"），
 * 分段控件一眼看得到全部档位和当前位置，也不用先点开再拖。
 *
 * [current] 是**这一层存的值**（null = 跟随上级），[effective] 是解析后的生效值 ——
 * 两者一样时必须区分清楚：滑块停在"跟随"，而下面那行告诉我们最后生效的是什么。
 */
@Composable
internal fun ThinkingEffortSlider(
    current: String?,
    effective: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = ThinkingOptions.indexOfFirst { it.value == current }.coerceAtLeast(0)
    var rowWidth by remember { mutableIntStateOf(0) }
    // 首次量到宽度时直接对齐：从别的页面回来时不该看到滑块从最左边滑过来
    var measured by remember { mutableStateOf(false) }
    val cell = rowWidth.toFloat() / ThinkingOptions.size
    val pillX by animateFloatAsState(
        targetValue = index * cell,
        animationSpec = if (!measured) snap() else tween(200, easing = Motion.Standard),
        label = "effort-pill",
    )
    LaunchedEffect(cell) { if (cell > 0f) measured = true }
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("思考强度", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (current == null) "跟随 → ${reasoningLabel(effective)}" else reasoningLabel(effective),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Space.s))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
                .border(1.dp, outline, RoundedCornerShape(Radius.pill))
                .padding(3.dp),
        ) {
            if (rowWidth > 0) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(pillX.roundToInt(), 0) }
                        .width(with(LocalDensity.current) { cell.toDp() })
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                )
            }
            Row(
                modifier = Modifier.fillMaxSize().onSizeChanged { rowWidth = it.width },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThinkingOptions.forEach { option ->
                    val selected = option.value == current
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(Radius.pill))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSelect(option.value) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
