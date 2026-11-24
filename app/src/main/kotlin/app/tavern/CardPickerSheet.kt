package app.tavern

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
 * 新建会话前的选卡内容，放在 [GlassSheetScaffold] 里（由 `TabsRoot` 渲染，才有真磨砂）。
 *
 * 为什么要这一步：以前「新会话」直接拿上次的卡建，想换人聊时很容易建到错的地方，
 * 而且从列表上完全看不出来。现在必须点一张卡才建。
 */
@Composable
fun CardPickerBody(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val palette = LocalTavernPalette.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        items(state.cards, key = { it.id }) { card ->
            val shape = RoundedCornerShape(Radius.card)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, palette.cardBorder, shape)
                    .clickable { viewModel.pickCardAndStart(card.id) },
            ) {
                CardAvatar(
                    cardId = card.id,
                    fallbackText = card.name,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                )
                Text(
                    card.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}
