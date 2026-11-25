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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    // 删除确认在页面这一层发起（骨架之前），控制器要自己建再传进去
    val confirm = remember { ConfirmController() }

    GlassDetailScaffold(
        title = if (draft.isNew) "新建档案" else "编辑档案",
        onBack = viewModel::closeProfileEditor,
        glass = state.visualEffects.effective() == VisualEffects.Glass,
        contentTopInset = 0.dp,

        confirmController = confirm,
        actions = {
            CapsuleIcon(
                icon = Icons.Filled.Check,
                contentDescription = "保存",
                enabled = draft.canSave,
            ) { viewModel.saveProfileDraft() }
        },
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                // 让位拆两半：底部留在外面，顶部挪进滚动层 —— 内容才能从页头底下穿过去，
                // 页头才是"半透明"而不是一条纯色（用户 2026-11-25 第 7 条）
                .padding(bottom = padding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding() + DetailScrollUnderTop)
                .padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { value -> viewModel.editProfileDraft { it.copy(name = value) } },
                label = { Text("名称") },
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
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
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

            if (draft.isNew) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "保存后设为默认",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = draft.isDefault,
                        onCheckedChange = { checked ->
                            viewModel.editProfileDraft { it.copy(isDefault = checked) }
                        },
                    )
                }
            }

            if (!draft.isNew) {
                Column(modifier = Modifier.padding(top = Space.l, bottom = Space.xl)) {
                    SecondaryAction(text = "删除档案", onClick = { confirmDelete = true })
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            controller = confirm,
            title = "删除「${draft.name}」？",
            message = "这条档案的 API Key 也会一起删除，删了找不回来。",
            onConfirm = {
                confirmDelete = false
                draft.id?.let(viewModel::deleteProfile)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}


