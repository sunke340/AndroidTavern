package app.tavern

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 角色卡网格：两列，每格是卡图（3:4）+ 名字 + 简介。
 *
 * 视觉按 C 方向：白底卡片 + 1px 描边，不用阴影（"线感"而不是"浮起感"）。
 * 点卡片 → 角色详情；开会话是详情页里的显式动作。
 */
@Composable
fun CharactersScreen(
    state: ChatViewModel.UiState,
    viewModel: ChatViewModel,
    // 滚动位置由上层持有：从角色详情返回时网格不该回到顶部。
    gridState: LazyGridState = rememberLazyGridState(),
    // 悬浮底栏压在内容上，网格底部要让出它的高度。
    bottomPadding: Dp = 0.dp,
) {
    val palette = LocalTavernPalette.current
    // 页头一直铺到状态栏后面，网格要让出"状态栏 + 页头"的高度
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val overscroll = rememberRubberOverscroll()

    LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().rubberOverscroll(overscroll),
            state = gridState,
            contentPadding = PaddingValues(
                start = Space.l,
                end = Space.l,
                top = PageHeaderHeight + Space.s + statusBar,
                bottom = Space.l + bottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(Space.m - 2.dp),
            verticalArrangement = Arrangement.spacedBy(Space.m - 2.dp),
        ) {
            items(state.cards, key = { it.id }) { card ->
                val shape = RoundedCornerShape(Radius.card)
                Column(
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = tween(Motion.Base, easing = Motion.Decelerate),
                            placementSpec = tween(Motion.Slow, easing = Motion.Standard),
                            fadeOutSpec = tween(Motion.Fast),
                        )
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
