package app.tavern

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 列表：行系统的全部实现（尺寸 token、行、分组外壳、分组外轮廓、行首头像）。
 *
 * 从 `Style.kt` 拆出来的（2026-11-24，P4）：那份文件 638 行里混了三件事 —— 间距/圆角 token、
 * 玻璃页头用的小件、行系统。行系统是改得最勤的一块，单独一份更好找。
 *
 * **同包内拆文件，import 代价为零**：调用方（二十几个页面）不需要加任何 import。
 * 之所以不按包拆，理由见 docs/dev/structure-audit.md 第二节。
 */

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

/** 行标题 15sp / 600：比 titleSmall（14sp/500）重一档，扫列表时先看到名字。角色卡名字同档。 */
internal val RowTitleStyle = TextStyle(
    fontSize = 15.sp,
    lineHeight = 21.sp,
    fontWeight = FontWeight.SemiBold,
)

/** 行副行 12sp / 17sp：原来是 labelSmall（11sp），在 3.5 倍密度上偏小。 */
internal val RowSubtitleStyle = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)

/** 行尾时间 11sp / 500 + 等宽数字（`tnum`）：时间刷新时右对齐的那一列不会左右抖。 */
internal val RowMetaStyle = TextStyle(
    fontSize = 11.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
)

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
            val piece = rowPieceAt(index, count)
            GroupPiece(piece = piece, dividerInset = dividerInset) { row(index, piece) }
        }
    }
}

/**
 * 由位置推片型：[ListGroup] 与手写的 `LazyColumn`（拖动排序那种必须懒加载）共用，
 * 免得两处各写一遍 `when`，改一处忘另一处。
 */
fun rowPieceAt(index: Int, count: Int): RowPiece = when {
    count <= 1 -> RowPiece.Single
    index == 0 -> RowPiece.Top
    index == count - 1 -> RowPiece.Bottom
    else -> RowPiece.Middle
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
@OptIn(ExperimentalFoundationApi::class)
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
    val tint = rememberPressTint(press)
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
