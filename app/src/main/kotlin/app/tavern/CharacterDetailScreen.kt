package app.tavern

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 角色详情页（只读 + 会话管理）。
 *
 * 这一版**不改卡内容**：改字段要写回 PNG，需要单独一批（素材先备份、写回后校验）。
 * 现在能做的事：看这张卡是什么、用这张卡开会话、进入它已有的会话、删掉这张卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterDetailScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val cardId = state.detailCardId ?: return
    val card = state.cards.firstOrNull { it.id == cardId }
    val sessions = state.sessions.filter { it.cardId == cardId }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = viewModel::closeDetail) { Text("←", fontSize = 28.sp) }
                },
                title = { Text(card?.name ?: "角色详情") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                CardAvatar(
                    cardId = cardId,
                    fallbackText = card?.name.orEmpty(),
                    modifier = Modifier
                        .size(110.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        card?.name ?: cardId,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (card?.imported == true) "导入的卡" else "随包带的卡",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "描述 ${card?.description?.length ?: 0} 字",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Button(
                onClick = { viewModel.startSessionForCard(cardId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("用这张卡开新会话") }

            Text("简介", style = MaterialTheme.typography.labelMedium)
            Text(
                card?.description?.ifBlank { "（这张卡没有描述）" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
            )

            Text("这张卡的会话（${sessions.size}）", style = MaterialTheme.typography.labelMedium)
            if (sessions.isEmpty()) {
                Text("还没有会话", style = MaterialTheme.typography.bodySmall)
            }
            sessions.forEach { session ->
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
                            Text(session.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${session.messageCount} 条",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(">", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("删除这张卡") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除角色卡？") },
            text = {
                Text("会连带删除它的 ${sessions.size} 个会话，不能撤销。卡文件也会从应用里删掉。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteCard(cardId)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}
