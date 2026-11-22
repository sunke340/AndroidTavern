package app.tavern

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 角色卡网格：两列，每格是卡图（3:4）+ 名字 + 简介。
 *
 * 视觉按 C 方向：白底卡片 + 1px 描边，不用阴影（"线感"而不是"浮起感"）。
 * 点卡片 → 角色详情；开会话是详情页里的显式动作。
 */
@Composable
fun CharactersScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val pickCard = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importCard(uri)
    }
    val palette = LocalTavernPalette.current

    PageScaffold(
        title = "角色卡",
        action = { SecondaryAction(if (state.importing) "导入中…" else "导入", onClick = {
            pickCard.launch(arrayOf("image/*"))
        }) },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Space.l),
            horizontalArrangement = Arrangement.spacedBy(Space.m - 2.dp),
            verticalArrangement = Arrangement.spacedBy(Space.m - 2.dp),
        ) {
            items(state.cards, key = { it.id }) { card ->
                val shape = RoundedCornerShape(Radius.card)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, palette.cardBorder, shape)
                        .clickable { viewModel.openDetail(card.id) },
                ) {
                    CardAvatar(
                        cardId = card.id,
                        fallbackText = card.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                    )
                    Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = Space.s)) {
                        Text(
                            card.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                        Text(
                            card.description.ifBlank { "（这张卡没有描述）" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}
