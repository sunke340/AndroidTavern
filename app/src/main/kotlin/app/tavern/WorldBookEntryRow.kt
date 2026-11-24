package app.tavern

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable

/**
 * 世界书条目行：**全局书与卡内嵌书共用这一份**。
 *
 * 两边的数据来源不同（全局书来自导入的 `.json`，卡内嵌书在卡的 `character_book` 里），
 * 但"一行条目长什么样、开关怎么动"必须一模一样 —— 卡那侧以前是手写的复选框行，
 * 副行只放一行关键字，跟全局书详情页对不上。
 *
 * 开关只写"应用内覆盖"，不改原始文件（书和卡都要能原样导回 PC 版）。
 *
 * @param content 条目正文，副行做两行预览
 * @param enabled 生效状态（文件里的开关 **叠加** 应用内覆盖之后的结果）
 * @param disabledInFile 文件里本来就是关的：应用内改了也不生效，开关置灰
 */
@Composable
fun WorldBookEntryRow(
    title: String,
    content: String,
    enabled: Boolean,
    disabledInFile: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ListRow(
        title = title,
        subtitle = entrySubtitle(content, disabledInFile),
        // 正文可能是一整段，两行够看出这条讲什么
        subtitleMaxLines = 2,
        trailing = {
            Switch(
                checked = enabled,
                enabled = !disabledInFile,
                onCheckedChange = onToggle,
            )
        },
    )
}

/** 副行：正文折成一行并截到 90 字；文件里关着的补一句，否则用户会以为开关坏了。 */
private fun entrySubtitle(content: String, disabledInFile: Boolean): String? = buildString {
    if (content.isNotBlank()) append(content.replace("\n", " ").take(90))
    if (disabledInFile) {
        if (isNotEmpty()) append(" · ")
        append("文件里已关闭")
    }
}.ifEmpty { null }
