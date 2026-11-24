package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 编辑角色卡（写回 PNG）。
 *
 * 只开放六个文本字段：名字 / 简介 / 人格 / 场景 / 开场白 / 示例对话。
 * **内嵌世界书、扩展字段、图片数据原样保留** —— 写回由 `PngCard.write` 只替换卡片 JSON，
 * `IDAT` 逐字节不动（P0-3 有单测）；写之前 `CardStore` 会留一份 `.bak.png`。
 *
 * 为什么不做成"什么都能改"：`raw` 里有大量我们不认识的字段，露出来改错一个键就是废卡。
 */
@Composable
fun CardEditScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val detail = state.detail
    val glass = state.visualEffects.effective() == VisualEffects.Glass

    if (detail == null) {
        GlassDetailScaffold(
            title = "编辑角色卡",
            onBack = viewModel::closeCardEditor,
            glass = glass,
        ) { padding ->
            Text("读取中…", modifier = Modifier.padding(padding).padding(Space.l))
        }
        return
    }

    // 草稿只在进入时取一次：用户正在改的字不该被别的状态刷新冲掉
    var name by remember(detail.id) { mutableStateOf(detail.name) }
    var description by remember(detail.id) { mutableStateOf(detail.description) }
    var personality by remember(detail.id) { mutableStateOf(detail.personality) }
    var scenario by remember(detail.id) { mutableStateOf(detail.scenario) }
    var firstMessage by remember(detail.id) { mutableStateOf(detail.firstMessage) }
    var messageExample by remember(detail.id) { mutableStateOf(detail.messageExample) }
    // 备选开场白：可增删改。空串在保存时会被剔掉（界面上"加一条"先塞占位）
    var greetings by remember(detail.id) { mutableStateOf(detail.alternateGreetings.toList()) }

    GlassDetailScaffold(
        title = "编辑角色卡",
        subtitle = "写回卡文件，原文件备份为 .bak.png",
        onBack = viewModel::closeCardEditor,
        glass = glass,
        actions = {
            TextButton(
                onClick = {
                    viewModel.saveCardFields(
                        name = name,
                        description = description,
                        personality = personality,
                        scenario = scenario,
                        firstMessage = firstMessage,
                        messageExample = messageExample,
                        alternateGreetings = greetings,
                    )
                },
            ) { Text("保存") }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .rubberOverscroll(rememberRubberOverscroll())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l, vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(
                "改动会写进这张卡的 PNG 文件本身；内嵌世界书与图片数据不会被碰。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field("名字", name, singleLine = true) { name = it }
            Field("简介（description）", description, minLines = 5) { description = it }
            Field("人格（personality）", personality, minLines = 2) { personality = it }
            Field("场景（scenario）", scenario, minLines = 2) { scenario = it }
            Field("开场白（first_mes）", firstMessage, minLines = 4) { firstMessage = it }
            Field("示例对话（mes_example）", messageExample, minLines = 4) { messageExample = it }

            Text(
                "备选开场白（${greetings.count { it.isNotBlank() }} 条）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            greetings.forEachIndexed { index, text ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { next ->
                            greetings = greetings.toMutableList().also { it[index] = next }
                        },
                        label = { Text("备选 ${index + 1}") },
                        minLines = 3,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    )
                    TextButton(
                        onClick = {
                            greetings = greetings.toMutableList().also { it.removeAt(index) }
                        },
                    ) { Text("删除这一条", color = MaterialTheme.colorScheme.error) }
                }
            }
            TextButton(onClick = { greetings = greetings + "" }) { Text("＋ 加一条备选开场白") }
        }
    }
}

/** 一个带标签的多行输入。 */
@Composable
private fun Field(
    label: String,
    value: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
    )
}
