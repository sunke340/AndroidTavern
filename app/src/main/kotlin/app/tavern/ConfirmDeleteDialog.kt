package app.tavern

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 不可逆删除的统一确认框：标题 + 一句后果 + 红色确认。
 *
 * 为什么抽出来：全 App 有 7 处删除（会话×2、角色卡×2、世界书、模型档案×2），原来每处手写一段
 * `AlertDialog`，于是同一个动作出现两种措辞，而且只有档案编辑那处的确认钮是红的。现在改措辞、
 * 改颜色都只改这里。
 *
 * 确认钮一律 `colorScheme.error`：不可逆操作里，这是唯一需要眼睛停一下的地方。
 *
 * 调用时**参数全部写名字**：`onConfirm` 与 `onDismiss` 都是 lambda，位置写反了编译器不报。
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "删除",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
