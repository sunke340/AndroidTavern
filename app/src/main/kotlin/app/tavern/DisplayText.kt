package app.tavern

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.tavern.core.regex.InlineMarkup

/**
 * 把显示层正则的输出（`<i>` / `<b>` / `<br>`）变成带样式的文本。
 *
 * **气泡与角色详情页开场白共用这一个入口**（用户 2026-11-25 第 7 条：正则也要作用到开场白）。
 * 只认 [InlineMarkup] 支持的那一小撮标签，其他标签原样显示 —— 不做 HTML 渲染。
 *
 * 斜体染成 [italicColor]（调用方传主题主色）：斜体在原版里就是动作/神态描写，
 * 和叙述同色会糊在一起（用户 2026-11-25 第 9 条）。粗体不改色，只加粗。
 */
internal fun markupText(text: String, italicColor: Color): AnnotatedString = buildAnnotatedString {
    for (run in InlineMarkup.parse(text)) {
        if (run.lineBreak) {
            append('\n')
            continue
        }
        val start = length
        append(run.text)
        if (run.italic || run.bold) {
            addStyle(
                SpanStyle(
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    color = if (run.italic) italicColor else Color.Unspecified,
                ),
                start,
                length,
            )
        }
    }
}
