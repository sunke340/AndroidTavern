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
 *   只是它改的是卡文件；我们不回写卡，所以存在本机）。
 *
 * 强制开启的行只靠**红**表示（6% 红底 + 红开关），**行里不写状态文案**：
 * 文案要么被长正文挤掉、要么把行撑宽（用户 2026-11-24）。含义由列表顶部那行
 * [WorldBookForcedHint] 说明，且只在真有强制条目时出现。
 *
 * @param checked 生效状态
 * @param forcedOn 应用内强制开启（文件/卡里关着、被本机打开）
 */
@Composable
fun WorldBookEntryRow(
    title: String,
    content: String,
    checked: Boolean,
    forcedOn: Boolean,
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
            // 正文可能是一整段，两行够看出这条讲什么
            subtitle = if (content.isBlank()) null else content.replace("\n", " ").take(90),
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

/**
 * 列表顶部的小字说明：**只在真有强制开启条目时**出现（没有就不占位）。
 *
 * 红是"本机覆盖了卡/书里的默认"的意思，不解释一句容易被当成出错了。
 */
@Composable
fun WorldBookForcedHint(modifier: Modifier = Modifier) {
    Text(
        text = "红色条目为强制开启",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

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
