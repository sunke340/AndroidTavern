package app.tavern

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 列表上方的搜索框（世界书条目页、世界书选择页共用）。
 *
 * 只做"输入框长什么样"，过滤逻辑归各页面：条目页的正文命中要读整本书的原文，
 * 那是 ViewModel 的事；选择页只有书名单，UI 侧过滤就够。
 */
@Composable
fun SearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(placeholder, style = MaterialTheme.typography.bodySmall)
        },
        singleLine = true,
        shape = RoundedCornerShape(Radius.group),
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = modifier.fillMaxWidth(),
    )
}
