package app.tavern

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 角色卡网格：两列，每格是卡图 + 名字 + 简介。
 *
 * 点卡片 → 角色详情（**不直接开会话**：开会话是详情页里的显式动作）。
 * 页头用共用的 [PageScaffold]，留白/按钮位置与「会话」「设置」两页一致。
 */
@Composable
fun CharactersScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val pickCard = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importCard(uri)
    }

    PageScaffold(
        title = "角色卡",
        action = {
            OutlinedButton(
                onClick = { pickCard.launch(arrayOf("image/*")) },
                enabled = !state.importing,
            ) {
                Text(if (state.importing) "导入中…" else "导入")
            }
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.cards, key = { it.id }) { card ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.openDetail(card.id) },
                ) {
                    Column {
                        CardAvatar(
                            cardId = card.id,
                            fallbackText = card.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f),
                        )
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                card.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                            )
                            Text(
                                card.description.ifBlank { "（这张卡没有描述）" },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}
