package app.tavern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * 模型档案编辑页（三级页：设置 → 模型 → 某条档案）。
 *
 * 编辑的是一份**草稿**（`UiState.profileDraft`）：点「保存」才写库、才写 Key。
 * 这样"打了一半的 Key"不会把能用的 Key 顶掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(state: ChatViewModel.UiState, viewModel: ChatViewModel) {
    val draft = state.profileDraft ?: return
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = viewModel::closeProfileEditor) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        text = if (draft.isNew) "新建档案" else "编辑档案",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    TextButton(
                        onClick = viewModel::saveProfileDraft,
                        enabled = draft.canSave,
                    ) {
                        Text("保存")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { value -> viewModel.editProfileDraft { it.copy(name = value) } },
                label = { Text("档案名（例如：DeepSeek 官网 / 本地 Ollama）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = { value -> viewModel.editProfileDraft { it.copy(baseUrl = value) } },
                label = { Text("Base URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.model,
                onValueChange = { value -> viewModel.editProfileDraft { it.copy(model = value) } },
                label = { Text("模型 ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.apiKey,
                onValueChange = { value -> viewModel.editProfileDraft { it.copy(apiKey = value) } },
                label = { Text("API Key（Keystore 加密，按档案分开存）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "本地 Ollama / LM Studio 通常不需要 Key，留空即可。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel("思考强度")
            SegmentedRow(
                options = EFFORT_OPTIONS,
                selected = draft.reasoningEffort,
                label = { key -> EFFORT_LABELS.getValue(key) },
                onSelect = { value -> viewModel.editProfileDraft { it.copy(reasoningEffort = value) } },
            )
            Text(
                "会作为 reasoning_effort 放进请求体。「不设」= 不发送这个字段（多数 provider 用默认值）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel("自定义请求头")
            OutlinedTextField(
                value = draft.headersText,
                onValueChange = { value -> viewModel.editProfileDraft { it.copy(headersText = value) } },
                label = { Text("一行一条：Name: value") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "中转站常用：X-Title、HTTP-Referer。值里可以有冒号（URL 不用转义）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!draft.isNew) {
                SectionLabel("这条档案")
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    SecondaryAction(
                        text = if (draft.isDefault) "已是默认" else "设为默认",
                        enabled = !draft.isDefault,
                        onClick = { draft.id?.let(viewModel::setDefaultProfile) },
                    )
                    SecondaryAction(text = "删除档案", onClick = { confirmDelete = true })
                }
                Text(
                    "删除会同时删掉这条档案的 Key（不可逆）。至少会留一条档案，" +
                        "删掉默认的后会自动把第一条顶上来。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(modifier = Modifier.padding(bottom = Space.xl)) {
                Text(
                    text = "保存后立即生效：默认档案就是聊天实际用的连接。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「${draft.name}」？") },
            text = { Text("这条档案的 API Key 也会一起删除，删了找不回来（不会影响其它档案）。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        draft.id?.let(viewModel::deleteProfile)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 思考强度的取值（空串 = 不发送该字段）。 */
private val EFFORT_OPTIONS = listOf("", "low", "medium", "high")

private val EFFORT_LABELS = mapOf(
    "" to "不设",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
)
