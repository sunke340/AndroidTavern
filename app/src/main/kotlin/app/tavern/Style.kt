package app.tavern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    /** 分组列表的容器：会话 / 模型 / 世界书这类"一组行"共用的圆角。 */
    val group: Dp = 18.dp

    /** 悬浮胶囊：底栏、弹层、卡片容器。 */
    val pill: Dp = 26.dp
}

/**
 * 列表行的尺寸：五处列表共用（会话 / 模型 / 世界书 / 正则 / 设置一级）。
 *
 * 为什么收成 token：之前每个页面各写各的（行高 64、头像 40、左右 16），
 * 世界书和正则还是手写 `Row`，同一屏里会出现三套边距。
 */
object RowToken {
    /** 两行行的最小高度；只有标题的行会更矮（`heightIn` 是最小值）。 */
    val height: Dp = 68.dp
    /** 行首头像的边长。**方形 + 圆角**，跟角色卡 3:4 的"方"语言一致，不再用圆头像。 */
    val avatar: Dp = 40.dp
    val avatarCorner: Dp = 10.dp
    val padH: Dp = 12.dp
    val padV: Dp = 11.dp
    /** 行尾时间列宽度：`08:12` / `昨天` / `11-18` 都放得下，右对齐后整列不抖。 */
    val meta: Dp = 44.dp
    /** 组内分隔线让出的距离：有头像时从文字左缘起（12 + 40 + 12）。 */
    val dividerInsetWithAvatar: Dp = 64.dp
    /** 没有头像的行（设置里的链接行）：只让出左右内边距。 */
    val dividerInset: Dp = 12.dp
}

/** 行标题 15sp / 600：比 titleSmall（14sp/500）重一档，扫列表时先看到名字。 */
private val RowTitleStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 21.sp,
    fontWeight = FontWeight.SemiBold,
)

/** 行副行 12sp / 17sp：原来是 labelSmall（11sp），在 3.5 倍密度上偏小。 */
private val RowSubtitleStyle = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)

/** 行尾时间 11sp / 500 + 等宽数字（`tnum`）：时间刷新时右对齐的那一列不会左右抖。 */
private val RowMetaStyle = TextStyle(
    fontSize = 11.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
)

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
 * 非磨砂页头的色罩浓度（**屏幕最顶端**的值，往下线性衰减到 0）。
 *
 * 2026-11-23 用户把"半透明色罩"定为**默认页头**（只有聊天页要磨砂，见 `GlassDetailScaffold` 的
 * `frostedHeader`）。试过三版：0.62 线性 → 太透；"饱和到页头底边再收尾"→ 浓度是**阶梯**的
 * （前 78% 一样浓、最后 28% 突然化开，看着像两块拼的）；现在回到**一条纯线性**，只把顶端提到 0.94。
 */
const val HeaderScrimAlpha = 0.94f

/**
 * 非磨砂页头的色罩：从 `y = 0` 一条**线性**渐变到页头盒子底部（收尾段末尾）的透明。
 *
 * 渐变自己铺满它所在的那个 Box，所以只需要背景色 —— **不要加拐点/平台段**：
 * 用户明确要"线性"，之前"前 78% 恒定 + 最后 28dp 化开"被判成"阶梯"。
 */
fun headerScrim(background: Color): Brush = Brush.verticalGradient(
    0.00f to background.copy(alpha = HeaderScrimAlpha),
    1.00f to Color.Transparent,
)

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
 * 行在组里的位置：决定哪两个角是圆的、哪条边画边框。
 *
 * 为什么要分片：分组容器在 `LazyColumn` 里没法是一个整体（那样一组几百行全要组合），
 * 所以每行自己画自己那一片。**中间的行不画上下边**，否则相邻两行各画一条 = 2px 双线。
 */
enum class RowPiece { Single, Top, Middle, Bottom }

/**
 * 一组行（圆角面板 + 组内分隔线），用于**非懒加载**的列表。
 *
 * 第一行/最后一行自动变成圆角，中间行只画左右两条边；分隔线从文字左缘起
 * （[dividerInset]）、**最后一行不画** —— 之前 `ListRow` 自己画通栏分隔线，
 * 列表末尾会多出一条悬空的直线。
 *
 * 用 `count + row(index, piece)` 而不是"把若干 composable lambda 装进 List"：
 * 后者要靠类型推断把 lambda 推成 `@Composable`，出错时只会在 CI 上炸一次。
 */
@Composable
fun ListGroup(
    count: Int,
    modifier: Modifier = Modifier,
    dividerInset: Dp = RowToken.dividerInset,
    row: @Composable (index: Int, piece: RowPiece) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        for (index in 0 until count) {
            val piece = when {
                count == 1 -> RowPiece.Single
                index == 0 -> RowPiece.Top
                index == count - 1 -> RowPiece.Bottom
                else -> RowPiece.Middle
            }
            GroupPiece(piece = piece, dividerInset = dividerInset) { row(index, piece) }
        }
    }
}

/**
 * 分组里的一片：背景、圆角与边线。
 *
 * 边框只画在"组的外轮廓"上（Top 画上/左/右，Middle 只画左/右，Bottom 画下/左/右），
 * 于是相邻两片之间只有一条 1px 线；圆角靠 `clip` 把直角边裁成弧。
 */
@Composable
fun GroupPiece(
    piece: RowPiece,
    modifier: Modifier = Modifier,
    dividerInset: Dp = RowToken.dividerInset,
    content: @Composable () -> Unit,
) {
    val palette = LocalTavernPalette.current
    val corner = Radius.group
    val shape = when (piece) {
        RowPiece.Single -> RoundedCornerShape(corner)
        RowPiece.Top -> RoundedCornerShape(topStart = corner, topEnd = corner)
        RowPiece.Bottom -> RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
        RowPiece.Middle -> RectangleShape
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .drawWithContent {
                drawContent()
                drawGroupOutline(piece, palette.cardBorder, corner.toPx())
            },
    ) {
        content()
        if (piece == RowPiece.Top || piece == RowPiece.Middle) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = dividerInset)
                    .height(1.dp)
                    .background(palette.divider),
            )
        }
    }
}

/**
 * 分组的外轮廓：**只画这一段该有的边**（Top＝左/上/右，Middle＝左/右，Bottom＝左/下/右）。
 *
 * 为什么不用 `Modifier.border`：
 * 1. 它画的是**整个轮廓**，中间行也会带上上下边 → 相邻两行各画一条 = 2px 双线；
 * 2. 边框挂在 `clip` 里面时，描边有一半在边界外被裁掉，1dp 会变成 0.5dp。
 * 所以自己按半线宽内缩画（`i = w / 2`），整条线完整落在面内、宽度不变。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGroupOutline(
    piece: RowPiece,
    color: Color,
    corner: Float,
) {
    val w = 1.dp.toPx()
    val inset = w / 2f
    val right = size.width - inset
    val bottom = size.height - inset
    val path = androidx.compose.ui.graphics.Path()
    when (piece) {
        RowPiece.Single -> path.addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                androidx.compose.ui.geometry.Rect(inset, inset, right, bottom),
                androidx.compose.ui.geometry.CornerRadius(corner),
            ),
        )

        RowPiece.Top -> {
            path.moveTo(inset, bottom)
            path.lineTo(inset, inset + corner)
            path.arcTo(
                androidx.compose.ui.geometry.Rect(
                    inset, inset, inset + corner * 2, inset + corner * 2,
                ),
                180f,
                90f,
                false,
            )
            path.lineTo(right - corner, inset)
            path.arcTo(
                androidx.compose.ui.geometry.Rect(
                    right - corner * 2, inset, right, inset + corner * 2,
                ),
                270f,
                90f,
                false,
            )
            path.lineTo(right, bottom)
        }

        RowPiece.Bottom -> {
            path.moveTo(inset, inset)
            path.lineTo(inset, bottom - corner)
            path.arcTo(
                androidx.compose.ui.geometry.Rect(
                    inset, bottom - corner * 2, inset + corner * 2, bottom,
                ),
                180f,
                -90f,
                false,
            )
            path.lineTo(right - corner, bottom)
            path.arcTo(
                androidx.compose.ui.geometry.Rect(
                    right - corner * 2, bottom - corner * 2, right, bottom,
                ),
                90f,
                -90f,
                false,
            )
            path.lineTo(right, inset)
        }

        RowPiece.Middle -> {
            path.moveTo(inset, 0f)
            path.lineTo(inset, size.height)
            path.moveTo(right, 0f)
            path.lineTo(right, size.height)
        }
    }
    drawPath(
        path = path,
        color = color,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w),
    )
}

/**
 * 列表行：左边可放头像、中间标题 + 一行副行、右边时间或操作。
 *
 * 与「卡片列表」的区别：C 方向用线不用影，所以列表页不要再用 Card 包一层。
 * **行本身不画背景和分隔线**：背景/圆角/边线交给 [ListGroup] / [GroupPiece]，
 * 这样懒加载与非懒加载两处才能长得一模一样。
 *
 * @param subtitle 副行（会话列表＝最后一条消息；设置链接＝当前值）
 * @param meta 行尾的次要信息（时间），右对齐 + 等宽数字；与 [trailing] 二选一
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun ListRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    meta: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    subtitleMaxLines: Int = 1,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    // 按下**立即**满色，松开才淡回去。
    // 之前两侧都是 120ms 的 tween：点一下通常 80~120ms 就抬手并跳页，底色还没长到能看见
    // 就已经进二级页了（用户 2026-11-24："还没出现就进去了"）。
    // 反馈属于"必须跟手"的那一类，不该有进入时长；退出时长只为不留一块硬色。
    val tint by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) {
            snap()
        } else {
            tween(Motion.Fast + 40, easing = Motion.Decelerate)
        },
        label = "row-tint",
    )
    val tintColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowToken.height)
            .drawBehind {
                if (tint > 0f) drawRect(tintColor.copy(alpha = 0.07f * tint))
            }
            .then(
                when {
                    // 涟漪关掉：它画在方形点击区里，圆角面板上会变成一块方角深色
                    onClick != null && onLongClick != null -> Modifier.combinedClickable(
                        interactionSource = press,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )

                    onClick != null -> Modifier.clickable(
                        interactionSource = press,
                        indication = null,
                        onClick = onClick,
                    )

                    else -> Modifier
                },
            )
            .padding(horizontal = RowToken.padH, vertical = RowToken.padV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(modifier = Modifier.padding(end = Space.m)) { leading() }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = RowTitleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = RowSubtitleStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (meta != null) {
            Box(
                modifier = Modifier
                    .padding(start = Space.s)
                    .width(RowToken.meta)
                    // 时间与标题的字号不同，抬 3dp 后两者的上沿才在一条线上
                    .padding(top = if (subtitle != null) 3.dp else 0.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                Text(
                    text = meta,
                    style = RowMetaStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = Space.s)) { trailing() }
        }
    }
}

/** 行首的方形头像：角色卡就是一张竖图，方形 + 小圆角比圆头像更像"这张卡"。 */
@Composable
fun RowAvatar(cardId: String, fallbackText: String) {
    CardAvatar(
        cardId = cardId,
        fallbackText = fallbackText,
        modifier = Modifier
            .size(RowToken.avatar)
            .clip(RoundedCornerShape(RowToken.avatarCorner)),
    )
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
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Space.l, bottom = Space.s),
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

