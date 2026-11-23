package app.tavern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 间距阶梯。只允许用这里的值，避免 12/14/16 混着来。 */
object Space {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
}

/** 圆角阶梯。玻璃风格上来以后，大件（底栏胶囊、弹层）用 [pill]。 */
object Radius {
    val button: Dp = 10.dp
    val card: Dp = 12.dp
    val bubble: Dp = 14.dp

    /** 悬浮胶囊：底栏、弹层、卡片容器。 */
    val pill: Dp = 26.dp
}

/** 列表行最小高度（含触控区）。 */
val ListRowHeight: Dp = 64.dp

/** 页头高度：大标题 + 玻璃胶囊，内容从它下面开始（滚起来会从渐变底下穿过去）。 */
val PageHeaderHeight: Dp = 72.dp

/** 页头玻璃胶囊的高度。 */
val HeaderActionHeight: Dp = 44.dp

/**
 * 页头遮挡范围比页头本身再往下多出的高度。
 * 渐变必须在**这一段的末尾**才收到透明，否则内容贴着页头底边"一刀切"地出现（2026-11-23 用户要求）。
 */
val HeaderFadeTail: Dp = 28.dp

/**
 * 页头胶囊里的图标动作：无底色、只靠图标与主色，跟截图里那种玻璃胶囊一致。
 *
 * 图标只能从 `material-icons-core` 取（见 app/AGENTS.md），拿不准的就用文字。
 */
@Composable
fun CapsuleIcon(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    // 没有本地 source 时自己造一个（不在玻璃胶囊里用时也要能点）
    val press = LocalGlassPress.current ?: remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(HeaderActionHeight - 8.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = press,
                // 涟漪会画成"图标那块一个圆盘"，和整块玻璃对不上；按压反馈统一交给 GlassSurface
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 列表行：左边可放头像、右边可放操作，底部自带 1px 分隔线。
 *
 * 与「卡片列表」的区别：C 方向用线不用影，所以列表页不要再用 Card 包一层，
 * 否则屏幕上会出现"卡片叠分隔线"的双重分隔。
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun ListRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val palette = LocalTavernPalette.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ListRowHeight)
                .then(
                    when {
                        onClick != null && onLongClick != null -> Modifier.combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )

                        onClick != null -> Modifier.clickable { onClick() }
                        else -> Modifier
                    },
                )
                .padding(horizontal = Space.l, vertical = Space.s + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(modifier = Modifier.padding(end = Space.m)) { leading() }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            if (trailing != null) {
                Box(modifier = Modifier.padding(start = Space.s)) { trailing() }
            }
        }
        HorizontalDivider(color = palette.divider)
    }
}

/**
 * 等宽分段选择器：**每个选项 weight(1f)**，无论几个都均分铺满整行。
 *
 * 之前用 FilterChip，宽度跟文字走（"跟随系统"比"浅色"宽一倍），一排看着参差。
 */
@Composable
fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTavernPalette.current
    val shape = RoundedCornerShape(Radius.button)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, palette.cardBorder, shape),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else androidx.compose.ui.graphics.Color.Transparent,
                    )
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** 设置一级列表里的一行：左边标题，右边当前值 + 箭头。 */
@Composable
fun SettingLinkRow(title: String, value: String, onClick: () -> Unit) {
    ListRow(
        title = title,
        subtitle = value,
        trailing = {
            // 用文字箭头而不是图标：material-icons-core 里没有 KeyboardArrowRight
            // （它在 extended 包里，为一个箭头引 10MB 依赖不值得），自绘一个更省。
            Text(
                "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = onClick,
    )
}

/** 分节标题（设置页、配置标签里用）。 */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.l, bottom = Space.xs),
    )
}

/** 页头右边的实心按钮（新建 / 发送这类主操作）。 */
@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Radius.button),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Space.m + 2.dp,
            vertical = Space.s - 1.dp,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 页头右边的次要按钮（导入这类）。 */
@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Radius.button),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Space.m + 2.dp,
            vertical = Space.s - 1.dp,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 会话时间：`11-22 14:20`，与卡名一起构成列表行的两行信息。 */
fun formatSessionTime(epochMillis: Long): String = sessionTimeFormatter.format(
    java.time.Instant.ofEpochMilli(epochMillis),
)

private val sessionTimeFormatter: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm", java.util.Locale.US)
        .withZone(java.time.ZoneId.systemDefault())

/** 空态提示：居中一段说明，不要做成"空白页"。 */
@Composable
fun EmptyHint(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 玻璃表面共用的按压来源。
 *
 * 为什么用 CompositionLocal：页头/详情页的"玻璃胶囊 + 图标"是两层写的（胶囊在 PageHeader，
 * 图标由调用方传进来）。按压要同时作用到胶囊（压暗+缩放）和图标（点击），
 * 两边得拿**同一个** interaction source；把 source 藏在 Local 里，调用方就不用改签名。
 */
val LocalGlassPress = staticCompositionLocalOf<androidx.compose.foundation.interaction.MutableInteractionSource?> { null }

