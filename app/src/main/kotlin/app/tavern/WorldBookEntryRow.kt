package app.tavern

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 世界书条目行：**全局书与卡内嵌书共用这一份**。
 *
 * 两边的数据来源不同（全局书来自导入的 `.json`，卡内嵌书在卡的 `character_book` 里），
 * 但"一行条目长什么样、开关怎么动"必须一模一样。
 *
 * 开关是**三态**的，界面上仍是两态按钮：
 * - 文件/卡里写着开 → 关掉就是本地覆盖；
 * - 文件/卡里写着关 → 打开就是**强制开启**（原版在 Character Lore 里也能这么做，
 *   只是它改的是卡文件；我们不回写卡，所以存在本机）。强制开启的行用红标记：
 *   整行 6% 红底 + 红色开关 + 副行末尾「强制开启」—— 红色只做标记，正文文字不变色。
 *
 * @param checked 生效状态
 * @param fileEnabled 文件/卡里写的是开还是关
 * @param forcedOn 应用内强制开启（只有 [fileEnabled] 为 false 时才可能为 true）
 * @param defaultOffNote 文件里关着、又没强制开启时副行末尾那句（"卡里默认关闭" / "文件里默认关闭"）
 */
@Composable
fun WorldBookEntryRow(
    title: String,
    content: String,
    checked: Boolean,
    fileEnabled: Boolean,
    forcedOn: Boolean,
    defaultOffNote: String,
    onToggle: (Boolean) -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    Box(
        modifier = if (forcedOn) {
            Modifier.fillMaxWidth().background(error.copy(alpha = 0.06f))
        } else {
            Modifier
        },
    ) {
        ListRow(
            title = title,
            subtitle = entrySubtitle(content, forcedOn, !fileEnabled, defaultOffNote),
            // 正文可能是一整段，两行够看出这条讲什么
            subtitleMaxLines = 2,
            trailing = {
                Switch(
                    checked = checked,
                    onCheckedChange = onToggle,
                    colors = if (forcedOn) {
                        SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onError,
                            checkedTrackColor = error,
                        )
                    } else {
                        SwitchDefaults.colors()
                    },
                )
            },
        )
    }
}

/** 副行：正文折成一行并截到 90 字；末尾标出"默认关闭"或"强制开启"。 */
private fun entrySubtitle(
    content: String,
    forcedOn: Boolean,
    fileOff: Boolean,
    defaultOffNote: String,
): String? = buildString {
    if (content.isNotBlank()) append(content.replace("\n", " ").take(90))
    val note = when {
        forcedOn -> "强制开启"
        fileOff -> defaultOffNote
        else -> null
    }
    if (note != null) {
        if (isNotEmpty()) append(" · ")
        append(note)
    }
}.ifEmpty { null }

/** 条目页右上角的批量菜单：全部开启 / 全部关闭 / 恢复默认（两页共用）。 */
@Composable
fun WorldBookBulkMenu(onAction: (BookBulkMode) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        CapsuleIcon(
            icon = Icons.Filled.MoreVert,
            contentDescription = "更多",
        ) { menuOpen = true }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            BookBulkMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        menuOpen = false
                        onAction(mode)
                    },
                )
            }
        }
    }
}
