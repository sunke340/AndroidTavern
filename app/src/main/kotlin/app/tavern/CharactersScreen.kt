package app.tavern

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 角色卡列表。批次 1 先做"能选能导入"；头像网格、角色详情、世界书入口在批次 2。
 */
@Composable
fun CharactersScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val pickCard = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importCard(uri)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("角色卡", style = MaterialTheme.typography.titleLarge)
                Text(
                    "共 ${state.cards.size} 张；点一张就切到它的会话",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            OutlinedButton(
                onClick = { pickCard.launch(arrayOf("image/*")) },
                enabled = !state.importing,
            ) {
                Text(if (state.importing) "导入中…" else "导入")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.cards, key = { it.id }) { card ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectCard(card.id) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                card.name,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                if (card.imported) "导入的卡" else "随包带的卡",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (state.cardId == card.id) {
                            Text("当前", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
