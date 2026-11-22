package app.tavern

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 会话列表：最近一次活动排在最上面。点一行进聊天。 */
@Composable
fun SessionsScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("会话", style = MaterialTheme.typography.titleLarge)
                Text(
                    state.status.ifBlank { "共 ${state.sessions.size} 个会话" },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            OutlinedButton(onClick = viewModel::newSession) { Text("新建") }
        }

        if (state.sessions.isEmpty()) {
            Text(
                "还没有会话：去「角色」选一张卡，或点「新建」。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.sessions, key = { it.id }) { session ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.openSession(session.id) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(session.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${session.cardName}｜${session.messageCount} 条",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        TextButton(onClick = { viewModel.deleteSession(session.id) }) { Text("删") }
                    }
                }
            }
        }
    }
}
