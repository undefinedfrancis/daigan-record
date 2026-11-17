package com.daigan.record.feature.orders

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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.StatusTag
import com.daigan.record.ui.components.formatGameDate
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun NewOrderScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NewOrderViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // State-based navigation: a collector that attaches or reattaches after the commit
    // still sees the destination. Acknowledging before navigating stops a second
    // navigation from the same committed result (for example after a rotation).
    val createdOrderId = uiState.createdOrderId
    val navigationPending = uiState.createdNavigationPending
    LaunchedEffect(createdOrderId, navigationPending) {
        if (createdOrderId != null && navigationPending) {
            viewModel.acknowledgeCreatedNavigation(createdOrderId)
            onCreated(createdOrderId)
        }
    }

    NewOrderScreen(
        uiState = uiState,
        onBack = onBack,
        onOpenCreated = onCreated,
        onStartNewCreation = viewModel::startNewCreation,
        onSelectType = viewModel::selectType,
        onApplyTemplate = viewModel::applyTemplate,
        onClearTemplate = viewModel::clearTemplate,
        onTitle = viewModel::updateTitle,
        onPrice = viewModel::updatePrice,
        onSelectPricePreset = viewModel::selectPricePreset,
        onPaymentMode = viewModel::selectPaymentMode,
        onInitialPaymentEnabled = viewModel::setInitialPaymentEnabled,
        onInitialAmount = viewModel::updateInitialAmount,
        onInitialChannel = viewModel::selectInitialChannel,
        onInitialNote = viewModel::updateInitialNote,
        onInitialReceivedAt = viewModel::updateInitialReceivedAt,
        onNotes = viewModel::updateNotes,
        onDeadline = viewModel::setDeadline,
        onStartDate = viewModel::setStartDate,
        onDuration = viewModel::setDurationText,
        onEndDate = viewModel::setEndDate,
        onServiceMode = viewModel::setServiceDayMode,
        onServiceWeekday = viewModel::toggleServiceWeekday,
        onSelectHostingMode = viewModel::selectHostingMode,
        onTargetSessions = viewModel::updateTargetSessions,
        onZone = viewModel::setZoneText,
        onDailyRefresh = viewModel::setDailyRefresh,
        onWeeklyDay = viewModel::setWeeklyRefreshDay,
        onWeeklyTime = viewModel::setWeeklyRefreshTime,
        onMonthlyRefreshDays = viewModel::setMonthlyRefreshDaysText,
        onAddTask = viewModel::addTask,
        onRemoveTask = viewModel::removeTask,
        onUpdateTask = viewModel::updateTask,
        onSubmit = viewModel::submit,
        snackbar = snackbar,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewOrderScreen(
    uiState: NewOrderUiState,
    onBack: () -> Unit,
    onOpenCreated: (String) -> Unit,
    onStartNewCreation: () -> Unit,
    onSelectType: (OrderType) -> Unit,
    onApplyTemplate: (String) -> Unit,
    onClearTemplate: () -> Unit,
    onTitle: (String) -> Unit,
    onPrice: (String) -> Unit,
    onSelectPricePreset: (String) -> Unit,
    onPaymentMode: (PaymentMode) -> Unit,
    onInitialPaymentEnabled: (Boolean) -> Unit,
    onInitialAmount: (String) -> Unit,
    onInitialChannel: (PaymentChannel) -> Unit,
    onInitialNote: (String) -> Unit,
    onInitialReceivedAt: (Instant) -> Unit,
    onNotes: (String) -> Unit,
    onDeadline: (LocalDate?) -> Unit,
    onStartDate: (LocalDate) -> Unit,
    onDuration: (String) -> Unit,
    onEndDate: (LocalDate) -> Unit,
    onZone: (String) -> Unit,
    onDailyRefresh: (LocalTime) -> Unit,
    onWeeklyDay: (DayOfWeek) -> Unit,
    onWeeklyTime: (LocalTime) -> Unit,
    onMonthlyRefreshDays: (String) -> Unit = {},
    onAddTask: () -> Unit,
    onRemoveTask: (Int) -> Unit,
    onUpdateTask: (Int, (TaskDraft) -> TaskDraft) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onServiceMode: (Boolean) -> Unit = {},
    onServiceWeekday: (DayOfWeek) -> Unit = {},
    onSelectHostingMode: (HostingMode) -> Unit = {},
    onTargetSessions: (String) -> Unit = {},
) {
    val createdOrderId = uiState.createdOrderId
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PageHeader(
                title = "新增订单",
                subtitle = "老板已确定，选择账号后配置订单",
                onBack = onBack,
            )
            when {
                uiState.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                uiState.loadError != null -> Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(title = "无法创建订单", description = checkNotNull(uiState.loadError))
                }

                // Terminal state: this form instance already committed an order. The
                // form is not shown again, so it cannot be submitted as a new order.
                createdOrderId != null -> CreatedOrderResult(
                    onOpen = { onOpenCreated(createdOrderId) },
                    onStartNewCreation = onStartNewCreation,
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = uiState.owner?.let { "${it.nickname} · ${it.displayNumber}" }
                                        ?: "老板",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = uiState.account?.let {
                                        "${it.gameName} · ${it.server} · ${it.uid}"
                                    } ?: "游戏账号",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }

                    item {
                        SectionTitle("订单类型")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = uiState.type == OrderType.HOSTING,
                                onClick = { onSelectType(OrderType.HOSTING) },
                                label = { Text("长期托管") },
                            )
                            FilterChip(
                                selected = uiState.type == OrderType.SHORT_TASK,
                                onClick = { onSelectType(OrderType.SHORT_TASK) },
                                label = { Text("短期任务") },
                            )
                        }
                    }

                    item {
                        TemplateSelector(
                            state = uiState,
                            onApplyTemplate = onApplyTemplate,
                            onClearTemplate = onClearTemplate,
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = uiState.title,
                            onValueChange = onTitle,
                            label = { Text("订单名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (uiState.isShort) {
                        item {
                            DateField(
                                label = "交付期限（选填）",
                                date = uiState.deadlineDate,
                                onDate = onDeadline,
                                onClear = { onDeadline(null) },
                            )
                        }
                    } else {
                        item {
                            SectionTitle("托管方式")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !uiState.countBased,
                                    onClick = { onSelectHostingMode(HostingMode.DATE_RANGE) },
                                    label = { Text("按日期托管") },
                                )
                                FilterChip(
                                    selected = uiState.countBased,
                                    onClick = { onSelectHostingMode(HostingMode.COUNT_BASED) },
                                    label = { Text("打卡式托管") },
                                    modifier = Modifier.testTag("new-order-count-mode"),
                                )
                            }
                            if (uiState.countBased) {
                                Text(
                                    text = "只约定总次数，不设开始/截止日期和固定星期；" +
                                        "未打卡的日子不扣次数，达到次数即视为完成。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (uiState.countBased) {
                            item {
                                OutlinedTextField(
                                    value = uiState.targetSessionsText,
                                    onValueChange = onTargetSessions,
                                    label = { Text("目标打卡次数") },
                                    singleLine = true,
                                    isError = uiState.formErrors.containsKey("targetSessions"),
                                    supportingText = {
                                        uiState.formErrors["targetSessions"]?.let { Text(it) }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("new-order-target-sessions"),
                                )
                                Text(
                                    text = "每轮需完成全部任务才计 1 次，同一天可以完成多轮；" +
                                        "下一轮从空白进度开始，上一轮记录保留。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            item {
                                SectionTitle("托管期限")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        DateField(
                                            label = "开始日期",
                                            date = uiState.startDate,
                                            onDate = onStartDate,
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        OutlinedTextField(
                                            value = uiState.durationText,
                                            onValueChange = onDuration,
                                            label = { Text(if (uiState.serviceDayMode) "计划服务天数" else "托管天数") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                Row {
                                    FilterChip(selected = !uiState.serviceDayMode, onClick = { onServiceMode(false) }, label = { Text("连续日期") })
                                    FilterChip(selected = uiState.serviceDayMode, onClick = { onServiceMode(true) }, label = { Text("累计服务日") })
                                }
                                if (uiState.serviceDayMode) {
                                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        DayOfWeek.entries.forEach { day -> FilterChip(selected = day in uiState.serviceWeekdays,
                                            onClick = { onServiceWeekday(day) }, label = { Text(dayLabel(day)) }) }
                                    }
                                    Text("按所选星期的计划服务日累计；漏打卡不会延长，不含法定调休规则。", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    text = if (uiState.serviceDayMode) "非服务日不生成任务，续期沿用服务星期。" else "开始日计为第 1 天，可直接调整到期日。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                if (uiState.serviceDayMode) {
                                    val calculated = runCatching { com.daigan.record.core.time.GameCalendar.hostingEndDateForWeekdays(uiState.startDate,
                                        uiState.durationText.toIntOrNull()?.takeIf { it in 1..3650 } ?: error("invalid"), uiState.serviceWeekdays) }.getOrNull()
                                    Text(calculated?.let { "预计结束日期（含当天）：$it" } ?: "请填写有效服务天数并选择服务星期")
                                } else DateField(
                                    label = "到期日期（含当天）",
                                    date = uiState.endDate,
                                    onDate = onEndDate,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        item {
                            RefreshRulesEditor(
                                zoneText = uiState.zoneText,
                                onZone = onZone,
                                dailyRefresh = uiState.dailyRefresh,
                                onDailyRefresh = onDailyRefresh,
                                monthlyRefreshDaysText = uiState.monthlyRefreshDaysText,
                                onMonthlyRefreshDays = onMonthlyRefreshDays,
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionTitle(
                                if (uiState.isShort) "任务与进度" else "托管任务",
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onAddTask) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Text("添加任务", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }

                    uiState.tasks.forEachIndexed { index, task ->
                        item(key = "task-$index") {
                            TaskEditor(
                                index = index,
                                task = task,
                                hosting = !uiState.isShort,
                                onChange = { transform -> onUpdateTask(index, transform) },
                                onRemove = { onRemoveTask(index) },
                                canRemove = uiState.tasks.size > 1,
                            )
                        }
                    }

                    item {
                        SectionTitle(if (uiState.isShort) "整单价格与收款" else "本期价格与收款")
                        OutlinedTextField(
                            value = uiState.priceYuan,
                            onValueChange = onPrice,
                            label = {
                                Text(if (uiState.isShort) "约定总价（元）" else "本期约定价格（元）")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PricePresetMenu(
                            state = uiState,
                            onSelect = onSelectPricePreset,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            FilterChip(
                                selected = uiState.paymentMode == PaymentMode.ONE_TIME,
                                onClick = { onPaymentMode(PaymentMode.ONE_TIME) },
                                label = { Text("一次结清") },
                            )
                            FilterChip(
                                selected = uiState.paymentMode == PaymentMode.INSTALLMENTS,
                                onClick = { onPaymentMode(PaymentMode.INSTALLMENTS) },
                                label = { Text("分批收款") },
                            )
                        }
                        InitialPaymentEditor(
                            state = uiState,
                            onEnabled = onInitialPaymentEnabled,
                            onAmount = onInitialAmount,
                            onChannel = onInitialChannel,
                            onNote = onInitialNote,
                            onReceivedAt = onInitialReceivedAt,
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = uiState.notes,
                            onValueChange = onNotes,
                            label = { Text("订单备注（选填）") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                            onClick = onSubmit,
                            enabled = !uiState.saving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (uiState.saving) "正在创建…" else "创建订单")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The committed result of this form instance. It is reached after a successful submit
 * and stays until the user explicitly starts another creation flow, so the same
 * visible form can never be submitted twice as two different commands.
 */
@Composable
private fun CreatedOrderResult(
    onOpen: () -> Unit,
    onStartNewCreation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("new-order-created"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "订单已创建",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("new-order-created-title"),
                )
                Text(
                    text = "订单和首笔实收（如有）已经保存，这张表单不会再次建单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "需要另一张订单请选择“再建一张”。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = onOpen,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("new-order-created-open"),
        ) { Text("查看订单") }
        TextButton(
            onClick = onStartNewCreation,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("new-order-created-new"),
        ) { Text("再建一张") }
    }
}

/**
 * Optional template picker. A template only fills this form's defaults; it is not a
 * requirement for creating an order and never changes an existing one.
 */
@Composable
private fun TemplateSelector(
    state: NewOrderUiState,
    onApplyTemplate: (String) -> Unit,
    onClearTemplate: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .testTag("new-order-template-selector"),
        ) {
            SectionTitle("游戏模板（选填）")
            Text(
                text = "选择模板会填入适合当前订单类型的任务与刷新规则，之后仍可逐项修改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.templates.isEmpty()) {
                Text(
                    text = "模板库为空；可先在 设置 → 游戏模板 新建或导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = state.selectedTemplateId == null,
                        onClick = onClearTemplate,
                        label = { Text("不使用模板") },
                    )
                    state.templates.forEach { template ->
                        FilterChip(
                            selected = state.selectedTemplateId == template.id,
                            onClick = { onApplyTemplate(template.id) },
                            label = { Text(template.label) },
                        )
                    }
                }
            }
            state.selectedTemplateName?.let { name ->
                Text(
                    text = "当前模板：$name · ${state.selectedTemplateVersion.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            state.templateNotice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Dropdown of the applied template's price presets. Selecting one fills the price field
 * through the view model; the field stays editable afterwards, and this menu never
 * changes the price on its own. A template with no presets renders nothing here, so the
 * form looks and behaves exactly as it did before presets existed.
 */
@Composable
private fun PricePresetMenu(
    state: NewOrderUiState,
    onSelect: (String) -> Unit,
) {
    if (state.templatePricePresets.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val selected = state.templatePricePresets.firstOrNull { it.id == state.selectedPricePresetId }
    Box(modifier = Modifier.padding(top = 8.dp)) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("new-order-price-preset-trigger"),
        ) {
            Text(
                text = if (selected != null) {
                    "预设价格：${Money.formatCents(selected.amountCents)}"
                } else {
                    "选择预设价格"
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.templatePricePresets.forEachIndexed { index, preset ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = buildString {
                                append(Money.formatCents(preset.amountCents))
                                preset.label?.takeIf { it.isNotBlank() }
                                    ?.let { append("（$it）") }
                            },
                        )
                    },
                    onClick = {
                        onSelect(preset.id)
                        expanded = false
                    },
                    modifier = Modifier.testTag("new-order-price-preset-$index"),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("手动填写") },
                onClick = { expanded = false },
                modifier = Modifier.testTag("new-order-price-preset-manual"),
            )
        }
    }
    Text(
        text = "选择后会填入价格，仍可继续手动修改。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

/**
 * Optional first receipt captured with the order. It stays off by default so existing
 * unpaid creation behaviour is unchanged; the repository writes it in the same
 * transaction as the order.
 */
@Composable
private fun InitialPaymentEditor(
    state: NewOrderUiState,
    onEnabled: (Boolean) -> Unit,
    onAmount: (String) -> Unit,
    onChannel: (PaymentChannel) -> Unit,
    onNote: (String) -> Unit,
    onReceivedAt: (Instant) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "建单同时录入首笔实收", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "关闭时订单保持未收款，可稍后在详情中记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.initialPaymentEnabled, onCheckedChange = onEnabled)
    }
    if (!state.initialPaymentEnabled) return

    OutlinedTextField(
        value = state.initialAmountYuan,
        onValueChange = onAmount,
        label = { Text("首笔实收（元）") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
    Text(
        text = "实际到账时间（默认现在，可补记过去；与录入时间分开统计）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    InstantFields(
        instant = Instant.ofEpochMilli(state.initialReceivedAtMillis),
        onInstant = onReceivedAt,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        PaymentChannel.entries.forEach { channel ->
            FilterChip(
                selected = state.initialChannel == channel,
                onClick = { onChannel(channel) },
                label = { Text(channel.label) },
            )
        }
    }
    if (state.initialChannel == PaymentChannel.OTHER) {
        OutlinedTextField(
            value = state.initialNote,
            onValueChange = onNote,
            label = { Text("收款备注（选填，仅“其他”渠道）") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
    val price = state.priceCentsOrNull
    val initial = state.initialCentsOrNull
    val summary = when {
        price == null -> "请先填写有效的约定价格。"
        initial == null -> "请输入大于 0 且最多两位小数的首笔金额。"
        initial > price -> "首笔实收不能超过约定价格 ${Money.formatCents(price)}。"
        else -> "约定 ${Money.formatCents(price)} · 首笔实收 ${Money.formatCents(initial)} · " +
            "建单后待收 ${Money.formatCents(price - initial)}"
    }
    Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall,
        color = if (price != null && initial != null && initial <= price) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier.padding(top = 6.dp),
    )
    if (state.paymentMode == PaymentMode.ONE_TIME &&
        price != null &&
        initial != null &&
        initial != price
    ) {
        Text(
            text = "收款方式为“一次结清”：首笔实收需与约定价格一致。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(
    label: String,
    date: LocalDate?,
    onDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    var show by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = date?.let(::formatGameDate).orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Row {
                    if (date != null && onClear != null) {
                        TextButton(onClick = onClear) { Text("清除") }
                    }
                    TextButton(onClick = { show = true }) { Text("选择") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (show) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).toUtcMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onDate(it.toUtcLocalDate()) }
                        show = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    label: String,
    time: LocalTime,
    onTime: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    var show by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = "%02d:%02d".format(time.hour, time.minute),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                TextButton(onClick = { show = true }) { Text("选择") }
            },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshRulesEditor(
    zoneText: String,
    onZone: (String) -> Unit,
    dailyRefresh: LocalTime,
    onDailyRefresh: (LocalTime) -> Unit,
    monthlyRefreshDaysText: String = "",
    onMonthlyRefreshDays: (String) -> Unit = {},
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("刷新时间")
            com.daigan.record.ui.components.TimeZoneSelector(value = zoneText, onValue = onZone)
            TimeField(
                label = "每日刷新时间",
                time = dailyRefresh,
                onTime = onDailyRefresh,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedTextField(
                value = monthlyRefreshDaysText,
                onValueChange = onMonthlyRefreshDays,
                label = { Text("每月刷新日（如 1,15；留空按周）") },
                singleLine = true,
                supportingText = {
                    Text("设置后每周任务按「刷新到刷新」的月度窗口计算；该月没有的日号自动跳过。")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

        }
    }
}

@Composable
internal fun TaskEditor(
    index: Int,
    task: TaskDraft,
    hosting: Boolean,
    onChange: ((TaskDraft) -> TaskDraft) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "任务 ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (canRemove) {
                    TextButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除任务")
                        Text("删除")
                    }
                }
            }
            OutlinedTextField(
                value = task.title,
                onValueChange = { value -> onChange { it.copy(title = value) } },
                label = { Text("任务名称，例如：每日签到") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "记录方式",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = task.progressType == ProgressType.CHECKBOX,
                    onClick = { onChange { it.copy(progressType = ProgressType.CHECKBOX) } },
                    label = { Text("勾选完成") },
                )
                FilterChip(
                    selected = task.progressType == ProgressType.NUMERIC,
                    onClick = { onChange { it.copy(progressType = ProgressType.NUMERIC) } },
                    label = { Text("数值进度") },
                )
            }
            if (task.progressType == ProgressType.NUMERIC) {
                // UF-038: the mode is an explicit choice. Ordinary keeps per-occurrence
                // targets; cumulative records the current absolute total against one goal.
                Text(
                    text = "数值口径",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = task.effectiveNumericMode == NumericMode.PER_OCCURRENCE,
                        onClick = {
                            onChange { it.copy(numericMode = NumericMode.PER_OCCURRENCE) }
                        },
                        label = { Text("逐次目标") },
                        modifier = Modifier.testTag("task-numeric-mode-occurrence"),
                    )
                    FilterChip(
                        selected = task.effectiveNumericMode == NumericMode.CUMULATIVE,
                        onClick = {
                            onChange { it.copy(numericMode = NumericMode.CUMULATIVE) }
                        },
                        label = { Text("累计总值") },
                        modifier = Modifier.testTag("task-numeric-mode-cumulative"),
                    )
                }
                if (task.effectiveNumericMode == NumericMode.CUMULATIVE) {
                    Text(
                        text = "打卡时填写当前累计总值（例如已累计 10，而不是本次 +10），" +
                            "系统按目标值判断是否达成。",
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
                        onValueChange = { value -> onChange { it.copy(startValue = value) } },
                        label = { Text("起始值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = task.targetValue,
                        onValueChange = { value -> onChange { it.copy(targetValue = value) } },
                        label = { Text("目标值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = task.unit,
                    onValueChange = { value -> onChange { it.copy(unit = value) } },
                    label = { Text("单位，例如：抽 / %") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = "生效日期（选填）",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = task.activeFrom,
                    onValueChange = { value -> onChange { it.copy(activeFrom = value) } },
                    label = { Text("开始生效") },
                    placeholder = { Text("2026-10-01") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("task-active-from-$index"),
                )
                OutlinedTextField(
                    value = task.activeUntil,
                    onValueChange = { value -> onChange { it.copy(activeUntil = value) } },
                    label = { Text("停止生效") },
                    placeholder = { Text("2026-12-31") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("task-active-until-$index"),
                )
            }
            Text(
                text = "留空表示随订单始终有效；日期按游戏日归属。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hosting) {
                Text(
                    text = "重复周期",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    // One-time work has no hosting occurrence; it is offered by
                    // short-order templates only and never silently becomes DAILY.
                    RecurrenceType.entries
                        .filter { it != RecurrenceType.ONE_TIME }
                        .forEach { recurrence ->
                            FilterChip(
                                selected = task.recurrence == recurrence,
                                onClick = { onChange { it.copy(recurrence = recurrence) } },
                                label = { Text(recurrenceLabel(recurrence)) },
                            )
                        }
                }
                when (task.recurrence) {
                    RecurrenceType.SELECTED_WEEKDAYS -> {
                        Text(
                            text = "执行星期",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            DayOfWeek.entries.forEach { day ->
                                FilterChip(
                                    selected = day in task.weekdays,
                                    onClick = {
                                        onChange {
                                            it.copy(
                                                weekdays = if (day in it.weekdays) {
                                                    it.weekdays - day
                                                } else {
                                                    it.weekdays + day
                                                },
                                            )
                                        }
                                    },
                                    label = { Text(dayLabel(day)) },
                                )
                            }
                        }
                    }

                    RecurrenceType.MONTHLY -> OutlinedTextField(
                        value = task.monthDay,
                        onValueChange = { value -> onChange { it.copy(monthDay = value) } },
                        label = { Text("每月日号（如 1,15；当月无该日跳过）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RecurrenceType.EVERY_N_DAYS -> {
                        OutlinedTextField(
                            value = task.everyNDays,
                            onValueChange = { value -> onChange { it.copy(everyNDays = value) } },
                            label = { Text("每隔若干天（从托管开始日算起）") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
                    }

                    RecurrenceType.WEEKLY -> Text(
                        text = "每周一次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    RecurrenceType.ONE_TIME -> Text(
                        text = "一次性任务没有游戏日期，只能用于短期单；请为托管任务选择周期。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    RecurrenceType.DAILY -> Unit
                }
            }
        }
    }
}

private fun recurrenceLabel(type: RecurrenceType): String = when (type) {
    RecurrenceType.ONE_TIME -> "一次性"
    RecurrenceType.MONTHLY -> "每月固定日号"
    RecurrenceType.DAILY -> "每日"
    RecurrenceType.WEEKLY -> "每周"
    RecurrenceType.SELECTED_WEEKDAYS -> "指定星期"
    RecurrenceType.EVERY_N_DAYS -> "每隔若干天"
}

private fun dayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
