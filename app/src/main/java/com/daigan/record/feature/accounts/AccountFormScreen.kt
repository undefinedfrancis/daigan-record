package com.daigan.record.feature.accounts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.daigan.record.data.model.AccountResources
import com.daigan.record.data.model.AccountResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.AccountField
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.ui.components.PageHeader

@Composable
fun AccountFormScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountFormViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingDraft by viewModel.pendingDraft.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.completed) {
        if (uiState.completed) onDone()
    }

    AccountFormScreen(
        uiState = uiState,
        onBack = onDone,
        onDraftChange = viewModel::onDraftChange,
        onSelectLoginMethod = viewModel::selectLoginMethod,
        onSelectTemplate = viewModel::selectTemplate,
        onSelectCategory = viewModel::selectCategory,
        onCreateCategory = viewModel::createCategory,
        onSave = viewModel::save,
        modifier = modifier,
        hasPendingDraft = pendingDraft != null,
        onRestoreDraft = viewModel::restoreDraft,
        onDiscardDraft = viewModel::discardDraft,
    )
}

@Composable
internal fun AccountFormScreen(
    uiState: AccountFormUiState,
    onBack: () -> Unit,
    onDraftChange: ((AccountDraft) -> AccountDraft) -> Unit,
    onSelectLoginMethod: (LoginMethod) -> Unit,
    onSelectTemplate: (String?) -> Unit,
    onSelectCategory: (String?) -> Unit = {},
    onCreateCategory: (String) -> Unit = {},
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    hasPendingDraft: Boolean = false,
    onRestoreDraft: () -> Unit = {},
    onDiscardDraft: () -> Unit = {},
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var serverMenuOpen by rememberSaveable { mutableStateOf(false) }
    var newCategoryName by rememberSaveable { mutableStateOf("") }
    val draft = uiState.draft
    val errors = uiState.errors

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = if (uiState.isEdit) "编辑游戏账号" else "新增游戏账号",
            subtitle = uiState.ownerNickname?.let { "所属老板：$it" },
            onBack = onBack,
        )
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (hasPendingDraft) {
                Text("此账号有未保存草稿。可恢复或丢弃；继续输入会替换旧草稿。")
                Row {
                    TextButton(onClick = onRestoreDraft) { Text("恢复草稿") }
                    TextButton(onClick = onDiscardDraft) { Text("丢弃草稿") }
                }
            } else {
                Text("未完成的输入会自动保留在本机。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDiscardDraft) { Text("丢弃当前草稿") }
            }
            OutlinedTextField(
                value = draft.gameName,
                onValueChange = { value -> onDraftChange { it.copy(gameName = value) } },
                label = { Text("游戏名称") },
                singleLine = true,
                isError = errors.containsKey(AccountField.GAME_NAME),
                supportingText = {
                    Text(errors[AccountField.GAME_NAME] ?: "可直接填写，也可从下方模板带入。")
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "默认游戏模板（选填）",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "只影响以后为该账号新建订单时预填的任务与刷新规则；" +
                        "已有订单、账号资料和收款都不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (uiState.templates.isEmpty()) {
                    Text(
                        text = "模板库为空，可先在 设置 → 游戏模板 新建或导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        FilterChip(
                            selected = draft.defaultTemplateId == null,
                            onClick = { onSelectTemplate(null) },
                            label = { Text("不使用") },
                        )
                        uiState.templates.forEach { template ->
                            FilterChip(
                                selected = draft.defaultTemplateId == template.id,
                                onClick = { onSelectTemplate(template.id) },
                                label = { Text(template.label) },
                            )
                        }
                    }
                }
                uiState.templateName?.let { label ->
                    Text(
                        text = "已选择：$label",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "账号分类（选填）",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "只用于按分类查找账号；删除分类只会让账号回到未分类，" +
                        "不会删除账号、订单、任务或历史。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .testTag("account-category-row"),
                ) {
                    FilterChip(
                        selected = draft.categoryId == null,
                        onClick = { onSelectCategory(null) },
                        label = { Text("未分类") },
                    )
                    uiState.categories.forEach { category ->
                        FilterChip(
                            selected = draft.categoryId == category.id,
                            onClick = { onSelectCategory(category.id) },
                            label = { Text(category.name) },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it },
                        label = { Text("新建分类") },
                        singleLine = true,
                        enabled = !uiState.categoryBusy && !uiState.isSaving,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("account-category-new-name"),
                    )
                    TextButton(
                        onClick = {
                            onCreateCategory(newCategoryName)
                            newCategoryName = ""
                        },
                        enabled = !uiState.categoryBusy && !uiState.isSaving &&
                            newCategoryName.isNotBlank(),
                    ) {
                        Text(if (uiState.categoryBusy) "创建中…" else "创建并选中")
                    }
                }
                uiState.categoryError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                uiState.categoryName?.let { name ->
                    Text(
                        text = "所属分类：$name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "区服", style = MaterialTheme.typography.titleSmall)
                if (uiState.serverOptions.isNotEmpty()) {
                    Text(
                        text = "可从该游戏的区服预设中下拉选择，也可直接输入；" +
                            "预设改动不会影响已保存的账号区服。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // UF-032: a dropdown of the selected game's presets. The text field
                    // below stays editable so custom values keep working.
                    Box {
                        TextButton(
                            onClick = { serverMenuOpen = true },
                            modifier = Modifier.testTag("account-server-dropdown"),
                        ) {
                            Text(
                                if (draft.server.isBlank()) {
                                    "选择常用区服（${uiState.serverOptions.size}）"
                                } else {
                                    "选择常用区服 · 当前 ${draft.server}"
                                },
                            )
                        }
                        DropdownMenu(
                            expanded = serverMenuOpen,
                            onDismissRequest = { serverMenuOpen = false },
                        ) {
                            uiState.serverOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        serverMenuOpen = false
                                        onDraftChange { it.copy(server = option) }
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.server,
                    onValueChange = { value -> onDraftChange { it.copy(server = value) } },
                    label = { Text("区服") },
                    singleLine = true,
                    isError = errors.containsKey(AccountField.SERVER),
                    supportingText = { errors[AccountField.SERVER]?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("account-server-field"),
                )
            }
            OutlinedTextField(
                value = draft.uid,
                onValueChange = { value -> onDraftChange { it.copy(uid = value) } },
                label = { Text("游戏 UID") },
                singleLine = true,
                isError = errors.containsKey(AccountField.UID),
                supportingText = { errors[AccountField.UID]?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.loginAccount,
                onValueChange = { value -> onDraftChange { it.copy(loginAccount = value) } },
                label = { Text("登录账号") },
                singleLine = true,
                isError = errors.containsKey(AccountField.LOGIN_ACCOUNT),
                supportingText = { errors[AccountField.LOGIN_ACCOUNT]?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(modifier = Modifier.selectableGroup()) {
                Text(text = "登录方式", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = draft.loginMethod == LoginMethod.PASSWORD,
                            onClick = { onSelectLoginMethod(LoginMethod.PASSWORD) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = draft.loginMethod == LoginMethod.PASSWORD,
                        onClick = null,
                    )
                    Text(text = "账号密码", modifier = Modifier.padding(start = 8.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = draft.loginMethod == LoginMethod.VERIFICATION_CODE,
                            onClick = { onSelectLoginMethod(LoginMethod.VERIFICATION_CODE) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = draft.loginMethod == LoginMethod.VERIFICATION_CODE,
                        onClick = null,
                    )
                    Text(text = "验证码登录", modifier = Modifier.padding(start = 8.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = draft.loginMethod == LoginMethod.QR_CODE,
                            onClick = { onSelectLoginMethod(LoginMethod.QR_CODE) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = draft.loginMethod == LoginMethod.QR_CODE,
                        onClick = null,
                    )
                    Text(text = "扫码登录", modifier = Modifier.padding(start = 8.dp))
                }
            }

            if (draft.loginMethod == LoginMethod.PASSWORD) {
                OutlinedTextField(
                    value = draft.password,
                    onValueChange = { value -> onDraftChange { it.copy(password = value) } },
                    label = { Text("登录密码") },
                    singleLine = true,
                    isError = errors.containsKey(AccountField.PASSWORD),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    supportingText = {
                        Text(
                            errors[AccountField.PASSWORD]
                                ?: "密码仅保存在本机应用资料和本机草稿，不会写入日志或分析数据。",
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = if (draft.loginMethod == LoginMethod.QR_CODE) {
                        "扫码登录只记录登录方式，应用不提供扫码；切换到非密码方式会清除已保存的密码。"
                    } else {
                        "验证码登录不会保存验证码；切换到验证码方式会清除已保存的密码。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "登录设备（选填）", style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    listOf("手机", "平板", "电脑").forEach { preset ->
                        FilterChip(
                            selected = draft.loginDevice == preset,
                            onClick = {
                                onDraftChange {
                                    it.copy(loginDevice = if (it.loginDevice == preset) "" else preset)
                                }
                            },
                            label = { Text(preset) },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft.loginDevice,
                    onValueChange = { value -> onDraftChange { it.copy(loginDevice = value) } },
                    label = { Text("登录设备名称") },
                    singleLine = true,
                    isError = errors.containsKey(AccountField.LOGIN_DEVICE),
                    supportingText = {
                        Text(
                            errors[AccountField.LOGIN_DEVICE]
                                ?: "可点选上方常用设备，也可填写自己的名称，例如“备用机”。",
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val resources = AccountResources.decode(draft.resourceNotes)
            Text("游戏资源（选填）", style = MaterialTheme.typography.titleSmall)
            Text("按需添加任意数量的资源，数量也可填写进度或说明。", style = MaterialTheme.typography.bodySmall)
            resources.entries.forEach { entry ->
                androidx.compose.runtime.key(entry.id) {
                    OutlinedTextField(
                        value = entry.name,
                        onValueChange = { value -> onDraftChange { current ->
                            val data = AccountResources.decode(current.resourceNotes)
                            current.copy(resourceNotes = AccountResources.encode(data.copy(entries = data.entries.map {
                                if (it.id == entry.id) it.copy(name = value) else it
                            })))
                        } },
                        label = { Text("资源名称") }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = entry.value,
                        onValueChange = { value -> onDraftChange { current ->
                            val data = AccountResources.decode(current.resourceNotes)
                            current.copy(resourceNotes = AccountResources.encode(data.copy(entries = data.entries.map {
                                if (it.id == entry.id) it.copy(value = value) else it
                            })))
                        } },
                        label = { Text("数量 / 详情") }, modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { onDraftChange { current ->
                        val data = AccountResources.decode(current.resourceNotes)
                        current.copy(resourceNotes = AccountResources.encode(data.copy(entries = data.entries.filterNot { it.id == entry.id })))
                    } }) { Text("移除此资源") }
                }
            }
            TextButton(onClick = { onDraftChange { current ->
                val data = AccountResources.decode(current.resourceNotes)
                current.copy(resourceNotes = AccountResources.encode(data.copy(entries = data.entries +
                    AccountResource(java.util.UUID.randomUUID().toString(), "", ""))))
            } }) { Text("添加资源") }
            OutlinedTextField(
                value = resources.notes,
                onValueChange = { value -> onDraftChange { current ->
                    current.copy(resourceNotes = AccountResources.encode(AccountResources.decode(current.resourceNotes).copy(notes = value)))
                } },
                label = { Text("其他资源备注 / 原有详情") },
                minLines = 3,
                supportingText = { Text("原有自由填写内容会完整保留。") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (uiState.ownerArchived) {
                Text(
                    text = "老板已归档，账号只能查看；请先在归档记录中恢复老板。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (uiState.saveError != null) {
                Text(
                    text = uiState.saveError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = onSave,
                enabled = !uiState.isSaving && !uiState.ownerArchived,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(if (uiState.isSaving) "保存中…" else "保存账号")
            }
        }
    }
}
