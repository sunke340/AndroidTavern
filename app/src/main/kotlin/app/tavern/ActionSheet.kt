package app.tavern

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 动作单里的一项。
 *
 * 图标留空：`material-icons-core` 只有一小撮图标，`Edit` 这类不保险 —— 为了一个抽屉里的
 * 小图标去引 `material-icons-extended`（+10MB、再多一轮构建）不值得，纯文字已经够清楚。
 */
class SheetAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 长按动作单：标题一行 + 若干动作。
 *
 * 会话 / 世界书 / 模型档案三处共用（之前各写一遍，间距和红色深浅必然走形）。
 * 点动作时**先关抽屉再执行**：动作通常会打开确认弹窗或另一个页面，
 * 抽屉留在下面会看到两层遮罩。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    title: String,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = Space.xl)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s),
            )
            actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            action.onClick()
                        }
                        .padding(horizontal = Space.l, vertical = Space.m + 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (action.destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

/**
 * 只有一个"删除"的动作单（会话、世界书用）。
 *
 * 点它只会关抽屉 + 交给调用方弹确认框，**不会直接删** —— 不可逆操作永远要问一次。
 */
@Composable
fun DangerActionSheet(
    title: String,
    actionLabel: String,
    onDismiss: () -> Unit,
    onAction: () -> Unit,
) {
    ActionSheet(
        title = title,
        onDismiss = onDismiss,
        actions = listOf(SheetAction(actionLabel, destructive = true, onClick = onAction)),
    )
}
