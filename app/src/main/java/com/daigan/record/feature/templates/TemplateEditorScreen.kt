package com.daigan.record.feature.templates

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TemplateCategoryDraft
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.data.model.TemplatePricePresetDraft
import com.daigan.record.data.model.TemplateTaskDraft
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader
import java.time.DayOfWeek
import java.time.LocalTime

@Composable
fun TemplateEditorScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TemplateEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.completed) {
        if (uiState.completed) onDone()
    }

    val requestBack: () -> Unit = {
        if (uiState.dirty && !uiState.saving) confirmDiscard = true else onDone()
    }

    // The system back gesture/button must not silently drop unsaved edits either.
    androidx.activity.compose.BackHandler(enabled = uiState.dirty && !uiState.saving) {
        confirmDiscard = true
    }

    TemplateEditorContent(
        uiState = uiState,
        onBack = requestBack,
        onDraftChange = viewModel::updateDraft,
        onAddCategory = viewModel::addCategory,
        onRemoveCategory = viewModel::removeCategory,
        onUpdateCategory = viewModel::updateCategory,
        onAddTask = viewModel::addTask,
        onRemoveTask = viewModel::removeTask,
        onUpdateTask = viewModel::updateTask,
        onAddPricePreset = viewModel::addPricePreset,
        onRemovePricePreset = viewModel::removePricePreset,
        onUpdatePricePreset = viewModel::updatePricePreset,
        onAddServerOption = viewModel::addServerOption,
        onRenameServerOption = viewModel::renameServerOption,
        onRemoveServerOption = viewModel::removeServerOption,
        onSave = viewModel::save,
        onCancel = requestBack,
        modifier = modifier,
    )

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃这次修改？") },
            text = { Text("未保存的分类、任务与默认值会丢失；已保存的模板不会改变。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onDone()
                    },
                    modifier = Modifier.testTag("template-discard-confirm"),
                ) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateEditorContent(
    uiState: TemplateEditorUiState,
    onBack: () -> Unit,
    onDraftChange: ((TemplateDraft) -> TemplateDraft) -> Unit,
    onAddCategory: () -> Unit,
    onRemoveCategory: (Int) -> Unit,
    onUpdateCategory: (Int, (TemplateCategoryDraft) -> TemplateCategoryDraft) -> Unit,
    onAddTask: (Int) -> Unit,
    onRemoveTask: (Int, Int) -> Unit,
    onUpdateTask: (Int, Int, (TemplateTaskDraft) -> TemplateTaskDraft) -> Unit,
    onAddPricePreset: () -> Unit,
    onRemovePricePreset: (Int) -> Unit,
    onUpdatePricePreset: (Int, (TemplatePricePresetDraft) -> TemplatePricePresetDraft) -> Unit,
    onAddServerOption: (String) -> Unit = {},
    onRenameServerOption: (String, String) -> Unit = { _, _ -> },
    onRemoveServerOption: (String) -> Unit = {},
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = uiState.draft
    val errors = uiState.errors
    var showServerAdd by remember { mutableStateOf(false) }
    var renameServerTarget by remember { mutableStateOf<String?>(null) }
    var removeServerTarget by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("template-editor"),
    ) {
        PageHeader(
            title = if (uiState.isNew) "新建游戏模板" else "编辑游戏模板",
            subtitle = uiState.origin?.let { originLabel(it) + " · 设置 ／ 游戏模板" }
                ?: "设置 ／ 游戏模板",
            onBack = onBack,
        )
        when {
            uiState.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.loadError != null -> Box(modifier = Modifier.padding(16.dp)) {
                EmptyStateCard(
                    title = "无法编辑模板",
                    description = checkNotNull(uiState.loadError),
                )
            }

            else -> LazyColumn(
                modifier = Modifier.testTag("template-editor-list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("基本信息", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = draft.gameName,
                                onValueChange = { value -> onDraftChange { it.copy(gameName = value) } },
                                label = { Text("游戏名称") },
                                singleLine = true,
                                isError = errors.containsKey("gameName"),
                                supportingText = { errors["gameName"]?.let { Text(it) } },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("template-game-name"),
                            )
                            OutlinedTextField(
                                value = draft.templateVersion,
                                onValueChange = { value ->
                                    onDraftChange { it.copy(templateVersion = value) }
                                },
                                label = { Text("模板版本") },
                                singleLine = true,
                                isError = errors.containsKey("templateVersion"),
                                supportingText = { errors["templateVersion"]?.let { Text(it) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = draft.gameVersionNotes,
                                onValueChange = { value ->
                                    onDraftChange { it.copy(gameVersionNotes = value) }
                                },
                                label = { Text("游戏版本备注（选填）") },
                                minLines = 2,
                                isError = errors.containsKey("gameVersionNotes"),
                                supportingText = { errors["gameVersionNotes"]?.let { Text(it) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = draft.defaultOrderTitle,
                                onValueChange = { value ->
                                    onDraftChange { it.copy(defaultOrderTitle = value) }
                                },
                                label = { Text("默认订单标题（选填）") },
                                singleLine = true,
                                isError = errors.containsKey("defaultOrderTitle"),
                                supportingText = {
                                    errors["defaultOrderTitle"]?.let { Text(it) }
                                        ?: Text(
                                            "建单时仅在标题未被手改时预填；留空不预填",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("template-default-order-title"),
                            )
                        }
                    }
                }

                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("托管方式", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !draft.countBased,
                                    onClick = {
                                        onDraftChange {
                                            it.copy(
                                                hostingMode = HostingMode.DATE_RANGE,
                                                hostingModeExplicit = true,
                                            )
                                        }
                                    },
                                    label = { Text("按日期托管") },
                                )
                                FilterChip(
                                    selected = draft.countBased,
                                    onClick = {
                                        onDraftChange {
                                            it.copy(
                                                hostingMode = HostingMode.COUNT_BASED,
                                                hostingModeExplicit = true,
                                            )
                                        }
                                    },
                                    label = { Text("打卡式托管") },
                                    modifier = Modifier.testTag("template-count-mode"),
                                )
                            }
                            if (draft.countBased) {
                                OutlinedTextField(
                                    value = draft.targetSessionsText,
                                    onValueChange = { value ->
                                        onDraftChange { it.copy(targetSessionsText = value) }
                                    },
                                    label = { Text("目标打卡次数") },
                                    singleLine = true,
                                    isError = errors.containsKey("targetSessions"),
                                    supportingText = {
                                        errors["targetSessions"]?.let { Text(it) }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("template-target-sessions"),
                                )
                                Text(
                                    text = "每轮需完成全部任务才计 1 次，同一天可完成多轮；" +
                                        "不设开始/截止日期，未打卡的日子不扣次数。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = "沿用按日期托管；新订单在建单时填写开始与到期日期。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "区服预设（该游戏）",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { showServerAdd = true },
                                    enabled = draft.gameName.isNotBlank(),
                                    modifier = Modifier.testTag("template-server-add"),
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                    Text("添加区服", modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                            Text(
                                text = "新建或编辑账号时，该游戏的区服可从这里预设的列表下拉选择；" +
                                    "也可继续手填。修改或删除预设不会改写已保存账号的区服。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            when {
                                draft.gameName.isBlank() -> Text(
                                    text = "先填写游戏名称，再维护该游戏的区服预设。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                uiState.serverOptions.isEmpty() -> Text(
                                    text = "该游戏还没有区服预设；账号表单仍可手填区服。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                else -> uiState.serverOptions.forEach { option ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(text = option, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { renameServerTarget = option }) {
                                            Text("改名")
                                        }
                                        TextButton(onClick = { removeServerTarget = option }) {
                                            Text("删除")
                                        }
                                    }
                                }
                            }
                            uiState.serverError?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("刷新规则", style = MaterialTheme.typography.titleMedium)
                            com.daigan.record.ui.components.TimeZoneSelector(
                                value = draft.zoneText,
                                onValue = { value -> onDraftChange { it.copy(zoneText = value) } },
                                error = errors["zone"],
                            )
                            TemplateTimeField(
                                label = "每日刷新时间",
                                time = draft.dailyRefresh,
                                onTime = { time -> onDraftChange { it.copy(dailyRefresh = time) } },
                            )

                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "任务分类与选项",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onAddCategory) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("添加分类", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    errors["categories"]?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    errors["tasks"]?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                draft.categories.forEachIndexed { categoryIndex, category ->
                    item(key = "category-$categoryIndex") {
                        CategoryEditor(
                            index = categoryIndex,
                            category = category,
                            errors = errors,
                            canRemove = draft.categories.size > 1,
                            onUpdate = { transform -> onUpdateCategory(categoryIndex, transform) },
                            onRemove = { onRemoveCategory(categoryIndex) },
                            onAddTask = { onAddTask(categoryIndex) },
                            onRemoveTask = { taskIndex -> onRemoveTask(categoryIndex, taskIndex) },
                            onUpdateTask = { taskIndex, transform ->
                                onUpdateTask(categoryIndex, taskIndex, transform)
                            },
                        )
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "接单价格预设（选填）",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onAddPricePreset) {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                    Text("添加价格", modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                            Text(
                                text = "接单时可从下拉列表直接选择这些价格；修改或删除预设不会影响已创建订单的价格。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            errors["pricePresets"]?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (draft.pricePresets.isEmpty()) {
                                Text(
                                    text = "还没有预设价格；接单时仍可手动输入。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            draft.pricePresets.forEachIndexed { index, preset ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    OutlinedTextField(
                                        value = preset.amountText,
                                        onValueChange = { value ->
                                            onUpdatePricePreset(index) {
                                                it.copy(amountText = value)
                                            }
                                        },
                                        label = { Text("价格（元）") },
                                        singleLine = true,
                                        isError = errors.containsKey("pricePreset.$index.amount"),
                                        supportingText = {
                                            errors["pricePreset.$index.amount"]?.let { Text(it) }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("template-price-preset-$index-amount"),
                                    )
                                    TextButton(
                                        onClick = { onRemovePricePreset(index) },
                                        modifier = Modifier.testTag("template-price-preset-$index-remove"),
                                    ) { Text("删除") }
                                }
                                OutlinedTextField(
                                    value = preset.label,
                                    onValueChange = { value ->
                                        onUpdatePricePreset(index) { it.copy(label = value) }
                                    },
                                    label = { Text("名称（选填）") },
                                    singleLine = true,
                                    isError = errors.containsKey("pricePreset.$index.label"),
                                    supportingText = {
                                        errors["pricePreset.$index.label"]?.let { Text(it) }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("template-price-preset-$index-label"),
                                )
                            }
                        }
                    }
                }

                if (uiState.errorMessages.isNotEmpty() || uiState.saveError != null) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                (uiState.errorMessages + listOfNotNull(uiState.saveError)).forEach {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Button(
                        onClick = onSave,
                        enabled = !uiState.saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("template-save"),
                    ) {
                        Text(if (uiState.saving) "保存中…" else "保存模板")
                    }
                    TextButton(
                        onClick = onCancel,
                        enabled = !uiState.saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("template-cancel"),
                    ) { Text("取消") }
                    Text(
                        text = "保存只影响以后新建订单的默认值；已有订单保留自己的任务与刷新快照。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showServerAdd) {
        TemplateOptionNameDialog(
            title = "添加区服预设",
            initial = "",
            onDismiss = { showServerAdd = false },
            onConfirm = { value ->
                showServerAdd = false
                onAddServerOption(value)
            },
        )
    }
    renameServerTarget?.let { option ->
        TemplateOptionNameDialog(
            title = "修改区服名称",
            initial = option,
            onDismiss = { renameServerTarget = null },
            onConfirm = { value ->
                renameServerTarget = null
                onRenameServerOption(option, value)
            },
        )
    }
    removeServerTarget?.let { option ->
        AlertDialog(
            onDismissRequest = { removeServerTarget = null },
            title = { Text("删除区服预设？") },
            text = { Text("删除「$option」后，已保存账号的区服保持不变。此操作不能撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    removeServerTarget = null
                    onRemoveServerOption(option)
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { removeServerTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TemplateOptionNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("区服名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CategoryEditor(
    index: Int,
    category: TemplateCategoryDraft,
    errors: Map<String, String>,
    canRemove: Boolean,
    onUpdate: ((TemplateCategoryDraft) -> TemplateCategoryDraft) -> Unit,
    onRemove: () -> Unit,
    onAddTask: () -> Unit,
    onRemoveTask: (Int) -> Unit,
    onUpdateTask: (Int, (TemplateTaskDraft) -> TemplateTaskDraft) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "分类 ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (canRemove) {
                    TextButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text("删除分类", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            OutlinedTextField(
                value = category.name,
                onValueChange = { value -> onUpdate { it.copy(name = value) } },
                label = { Text("分类名称") },
                singleLine = true,
                isError = errors.containsKey("category.$index"),
                supportingText = {
                    (errors["category.$index"] ?: errors["category.$index.tasks"])?.let {
                        Text(it)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("template-category-$index-name"),
            )
            HorizontalDivider()
            category.tasks.forEachIndexed { taskIndex, task ->
                TaskEditor(
                    categoryIndex = index,
                    taskIndex = taskIndex,
                    task = task,
                    errors = errors,
                    canRemove = category.tasks.size > 1,
                    onUpdate = { transform -> onUpdateTask(taskIndex, transform) },
                    onRemove = { onRemoveTask(taskIndex) },
                )
            }
            TextButton(onClick = onAddTask) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("添加任务", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun TaskEditor(
    categoryIndex: Int,
    taskIndex: Int,
    task: TemplateTaskDraft,
    errors: Map<String, String>,
    canRemove: Boolean,
    onUpdate: ((TemplateTaskDraft) -> TemplateTaskDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val key = "category.$categoryIndex.task.$taskIndex"
    val taskErrors = errors.filterKeys { it.startsWith(key) }.values.distinct()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "任务 ${taskIndex + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (canRemove) {
                    TextButton(onClick = onRemove) {
                        Text("删除")
                    }
                }
            }
            OutlinedTextField(
                value = task.title,
                onValueChange = { value -> onUpdate { it.copy(title = value) } },
                label = { Text("任务名称") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("template-task-$categoryIndex-$taskIndex-title"),
            )
            Text("记录方式", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = task.progressType == ProgressType.CHECKBOX,
                    onClick = { onUpdate { it.copy(progressType = ProgressType.CHECKBOX) } },
                    label = { Text("勾选完成") },
                )
                FilterChip(
                    selected = task.progressType == ProgressType.NUMERIC,
                    onClick = { onUpdate { it.copy(progressType = ProgressType.NUMERIC) } },
                    label = { Text("数值进度") },
                )
            }
            if (task.progressType == ProgressType.NUMERIC) {
                Text("数值口径", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = task.effectiveNumericMode == NumericMode.PER_OCCURRENCE,
                        onClick = {
                            onUpdate { it.copy(numericMode = NumericMode.PER_OCCURRENCE) }
                        },
                        label = { Text("逐次目标") },
                        modifier = Modifier.testTag(
                            "template-task-$categoryIndex-$taskIndex-mode-occurrence",
                        ),
                    )
                    FilterChip(
                        selected = task.effectiveNumericMode == NumericMode.CUMULATIVE,
                        onClick = {
                            onUpdate { it.copy(numericMode = NumericMode.CUMULATIVE) }
                        },
                        label = { Text("累计总值") },
                        modifier = Modifier.testTag(
                            "template-task-$categoryIndex-$taskIndex-mode-cumulative",
                        ),
                    )
                }
                if (task.effectiveNumericMode == NumericMode.CUMULATIVE) {
                    Text(
                        text = "建单后打卡时填写当前累计总值，目标只判断一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = task.startValue,
                        onValueChange = { value -> onUpdate { it.copy(startValue = value) } },
                        label = { Text("起始值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = task.targetValue,
                        onValueChange = { value -> onUpdate { it.copy(targetValue = value) } },
                        label = { Text("目标值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = task.unit,
                    onValueChange = { value -> onUpdate { it.copy(unit = value) } },
                    label = { Text("单位，例如：抽 / %") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("重复周期", style = MaterialTheme.typography.labelMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                RecurrenceType.entries.forEach { recurrence ->
                    FilterChip(
                        selected = task.recurrence == recurrence,
                        onClick = { onUpdate { it.copy(recurrence = recurrence) } },
                        label = { Text(templateRecurrenceLabel(recurrence)) },
                    )
                }
            }
            when (task.recurrence) {
                RecurrenceType.ONE_TIME -> Text(
                    text = "一次性任务只会进入短期单，不会生成每日托管打卡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                RecurrenceType.SELECTED_WEEKDAYS -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                selected = day in task.weekdays,
                                onClick = {
                                    onUpdate {
                                        it.copy(
                                            weekdays = if (day in it.weekdays) {
                                                it.weekdays - day
                                            } else {
                                                it.weekdays + day
                                            },
                                        )
                                    }
                                },
                                label = { Text(weekdayShortLabel(day)) },
                            )
                        }
                    }
                }

                RecurrenceType.MONTHLY -> OutlinedTextField(
                        value = task.monthDay,
                        onValueChange = { value -> onUpdate { it.copy(monthDay = value) } },
                        label = { Text("每月日号（如 1,15）（1–31；当月无该日则跳过）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RecurrenceType.EVERY_N_DAYS -> OutlinedTextField(
                    value = task.everyNDays,
                    onValueChange = { value -> onUpdate { it.copy(everyNDays = value) } },
                    label = { Text("每隔若干天（以托管期开始日为锚点）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                RecurrenceType.DAILY, RecurrenceType.WEEKLY -> Unit
            }
            Text("生效日期（选填）", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = task.activeFrom,
                    onValueChange = { value -> onUpdate { it.copy(activeFrom = value) } },
                    label = { Text("开始生效") },
                    placeholder = { Text("2026-10-01") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("template-task-$categoryIndex-$taskIndex-active-from"),
                )
                OutlinedTextField(
                    value = task.activeUntil,
                    onValueChange = { value -> onUpdate { it.copy(activeUntil = value) } },
                    label = { Text("停止生效") },
                    placeholder = { Text("2026-12-31") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("template-task-$categoryIndex-$taskIndex-active-until"),
                )
            }
            taskErrors.forEach { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateTimeField(
    label: String,
    time: LocalTime,
    onTime: (LocalTime) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = timeText(time),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { TextButton(onClick = { show = true }) { Text("选择") } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (show) {
        val state = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onTime(LocalTime.of(state.hour, state.minute))
                        show = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) { Text("取消") }
            },
        )
    }
}
