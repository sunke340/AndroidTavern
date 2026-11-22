package app.tavern

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 三个底部页面共用的页头骨架。
 *
 * 为什么要抽出来：之前三页各自手写页头，留白 12/16dp 不一、副标题有的页有有的没有、
 * 右侧按钮也各自对齐，切 tab 时标题会跳几个像素。
 *
 * 现在的规矩（新页面直接套，不要再手写）：
 * - 页头**固定 56dp**：左右 16dp、上下 8dp，标题垂直居中，右侧动作按钮槽固定 40dp；
 * - **没有副标题**：数量、空态这类信息交给内容区自己表达；
 * - 页头下面一条分隔线，三页内容都从同一条线开始；
 * - 临时结果（导入成功/失败、删除）走 Snackbar，不占页头。
 */
@Composable
fun PageScaffold(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PageHeaderHeight)
                .padding(horizontal = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                Box(
                    modifier = Modifier.heightIn(min = 40.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) { action() }
            }
        }

        HorizontalDivider(color = LocalTavernPalette.current.divider)

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}
