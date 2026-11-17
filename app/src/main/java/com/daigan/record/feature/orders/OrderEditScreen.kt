package com.daigan.record.feature.orders

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.core.time.GameCalendar
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.OrderEditDraft
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.TimeZoneSelector
import com.daigan.record.ui.components.formatGameDate
import java.time.DayOfWeek

/**
 * The single order editor (UF-030).
 *
 * Every former per-field correction is a section of this one page. It always saves with
 * one action (`viewModel.save()` → one repository transaction) and 取消 leaves without
 * writing anything; a rejected save keeps the draft and explains what to fix.
 *
 * Mode-aware: date-based hosting shows the selected period's dates and service plan,
 * 打卡式托管 shows only its agreed round count (never dates), a short order shows its
 * optional deadline.
 */
@Composable
fun OrderEditScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrderEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.saved.collect { onBack() }
    }
    LaunchedEffect(state.saveError) {
        state.saveError?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PageHeader(
                title = "修改订单",
                subtitle = state.detail?.let { "${it.order.title} · ${it.owner.nickname} · ${it.account.gameName}" },
                onBack = onBack,
            )
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.loadError != null -> Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(title = "无法打开编辑页", description = checkNotNull(state.loadError))
                }

                state.detail != null && state.draft != null -> OrderEditorBody(
                    detail = checkNotNull(state.detail),
                    state = state,
                    onDraftChange = viewModel::updateDraft,
                    onSelectPeriod = viewModel::selectPeriod,
                    onSave = viewModel::save,
                    onCancel = onBack,
                )
            }
        }
    }
}

@Composable
private fun OrderEditorBody(
    detail: OrderDetail,
    state: OrderEditUiState,
    onDraftChange: ((OrderEditDraft) -> OrderEditDraft) -> Unit,
    onSelectPeriod: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val draft = checkNotNull(state.draft)
    val hosting = draft.type == OrderType.HOSTING
    val countBased = hosting && detail.order.hostingMode == HostingMode.COUNT_BASED
    // Every period is selectable — including ended ones — so a historical price or a
    // permitted date correction stays reachable, exactly as the old dialogs allowed.
    val allPeriods = remember(detail) { detail.periods.sortedBy { it.periodIndex } }
    val period = allPeriods.firstOrNull { it.id == draft.periodId }
    val errors = state.errors
    // Converting an unstarted short order into hosting creates its first period on save.
    val identityChange = draft.accountId != detail.account.id || draft.type != detail.order.type
    // The lock belongs to the **selected** period's own end date and refresh rules, not to
    // whether the order happens to own a current/future period: an ended period keeps its
    // tasks and service plan read-only while name/notes/payment mode/price/permitted dates
    // stay savable.
    val historyLocked = hosting && !countBased && period?.isHistoryLockedAt(detail.now) == true
    val saveEnabled = !state.saving

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("order-edit-list"),
            // Tight top, consistent card rhythm: the page header already separates.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                EditorSection(title = "基本信息") {
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { value -> onDraftChange { it.copy(title = value) } },
                        label = { Text("订单名称") },
                        singleLine = true,
                        enabled = !state.saving,
                        isError = errors["title"] != null,
                        supportingText = {
                            Text(errors["title"] ?: "只修改显示名称，订单身份与历史记录保持不变")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.notes,
                        onValueChange = { value -> onDraftChange { it.copy(notes = value) } },
                        label = { Text("订单备注（可留空）") },
                        minLines = 2,
                        enabled = !state.saving,
                        isError = errors["notes"] != null,
                        supportingText = {
                            Text(errors["notes"] ?: "最多 ${OrderEditDraft.NOTES_MAX_LENGTH} 字；清空后保存即删除备注")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                EditorSection(title = "归属与订单类型") {
                    if (state.identityEditable) {
                        Text(
                            "仅适用于还没有任何打卡、收款、截图或续期记录的订单。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val accountOptions = state.owners
                            .filter { !it.owner.archived }
                            .flatMap { summary ->
                                summary.accounts
                                    .filter { !it.archived && it.ownerId == summary.owner.id }
                                    .map { account ->
                                        account.id to "${summary.owner.displayNumber} · ${summary.owner.nickname} · " +
                                            "${account.gameName} · ${account.server}"
                                    }
                            }
                        accountOptions.forEach { (id, label) ->
                            FilterChip(
                                selected = draft.accountId == id,
                                enabled = !state.saving,
                                onClick = { onDraftChange { it.copy(accountId = id) } },
                                label = { Text(label) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = draft.type == OrderType.HOSTING,
                                enabled = !state.saving,
                                onClick = { onDraftChange { it.copy(type = OrderType.HOSTING) } },
                                label = { Text("托管订单") },
                            )
                            FilterChip(
                                selected = draft.type == OrderType.SHORT_TASK,
                                enabled = !state.saving,
                                onClick = { onDraftChange { it.copy(type = OrderType.SHORT_TASK) } },
                                label = { Text("短期订单") },
                            )
                        }
                    } else {
                        Text(
                            "所属账号与订单类型不可修改。",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            reasonForLockedIdentity(detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                EditorSection(
                    title = when {
                        countBased -> "打卡约定"
                        hosting -> "服务范围"
                        else -> "交付期限"
                    },
                ) {
                    if (countBased) {
                        Text(
                            "打卡式托管没有开始/结束日期，也没有服务日计划：只约定总次数，" +
                                "每一轮全部任务完成才计 1 次。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = draft.targetSessions?.toString().orEmpty(),
                            onValueChange = { value ->
                                onDraftChange { it.copy(targetSessions = value.trim().toIntOrNull()) }
                            },
                            label = { Text("约定打卡次数") },
                            singleLine = true,
                            enabled = !state.saving,
                            isError = errors["targetSessions"] != null,
                            supportingText = {
                                Text(
                                    errors["targetSessions"]
                                        ?: "已完成 ${detail.completedSessionCount} 次；只能提高或保持，不能低于已完成次数",
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (hosting) {
                        if (allPeriods.isEmpty()) {
                            Text(
                                "该订单还没有期次：保存后按上面的日期建立第一期。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                if (allPeriods.size > 1) {
                                    "本次修改只影响所选期次；其他期次的日期、价格与任务保持不变。"
                                } else {
                                    "本次修改只影响该期次。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // All periods are offered: an ended one is exactly where a
                            // historical price or date correction is needed.
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            ) {
                                allPeriods.forEach { item ->
                                    FilterChip(
                                        selected = draft.periodId == item.id,
                                        enabled = !state.saving,
                                        onClick = { onSelectPeriod(item.id) },
                                        label = { Text(item.pickerLabel(detail.now)) },
                                    )
                                }
                            }
                            if (allPeriods.none { !it.isHistoryLockedAt(detail.now) }) {
                                Text(
                                    "该订单所有期次均已结束：仍可选择任一期次更正价格或允许的日期；历史任务与服务计划保持只读。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (allPeriods.size > 1) {
                                Text(
                                    "切换期次会重新载入该期次的日期、价格与服务配置（名称、备注、收款方式不受影响）；未保存的期次改动不会保留。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (historyLocked) {
                            // Field-specific restriction instead of a disabled Save: history
                            // stays read-only, while the corrections the repository still
                            // allows (name, notes, payment mode, price, permitted dates)
                            // remain available.
                            Text(
                                "所选期次已结束：仍可修改名称、备注、收款方式、价格，以及与历史记录不冲突的日期。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "该期次的任务与服务日计划已作为历史保留，不能修改；已有打卡或进度的期次不能改开始日期，结束日期也不能排除历史记录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (period != null) {
                            Text(
                                "所选期次：${formatGameDate(period.startDate)} 至 ${formatGameDate(period.endDate)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (draft.serviceDays == null) {
                            DateField(
                                label = "开始日期",
                                date = draft.startDate,
                                onDate = { date -> onDraftChange { it.copy(startDate = date) } },
                            )
                            DateField(
                                label = "结束日期",
                                date = draft.endDate,
                                onDate = { date -> onDraftChange { it.copy(endDate = date) } },
                            )
                        } else {
                            // A cumulative service plan derives both boundaries from the
                            // period start, so editing them directly would contradict it.
                            Text(
                                "开始日期：${draft.startDate?.let(::formatGameDate) ?: "—"}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "结束日期：${draft.endDate?.let(::formatGameDate) ?: "—"}（由服务计划推算）",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "启用累计服务天数后开始日期沿用所选期次的开始日；如需改开始日期，请先关闭服务计划。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (errors["dates"] != null) {
                            Text(
                                checkNotNull(errors["dates"]),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!historyLocked) {
                            Text(
                                "已有打卡或进度的期次不能改开始日期，结束日期也不能排除历史记录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = draft.deadlineDate != null,
                                    enabled = !state.saving,
                                    role = Role.Checkbox,
                                    onValueChange = { enabled ->
                                        onDraftChange {
                                            it.copy(deadlineDate = if (enabled) detail.today else null)
                                        }
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = draft.deadlineDate != null, onCheckedChange = null, enabled = !state.saving)
                            Text("设置交付截止日期")
                        }
                        if (draft.deadlineDate != null) {
                            DateField(
                                label = "截止日期",
                                date = draft.deadlineDate,
                                onDate = { date -> onDraftChange { it.copy(deadlineDate = date) } },
                                onClear = { onDraftChange { it.copy(deadlineDate = null) } },
                            )
                        }
                    }
                }
            }

            // A historical period keeps its service plan as history; the section is only
            // offered for the period whose plan may still change.
            if (hosting && !countBased && period != null && !historyLocked) {
                item {
                    EditorSection(title = "服务日计划（可选）") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = draft.serviceDays != null,
                                    enabled = !state.saving,
                                    role = Role.Checkbox,
                                    onValueChange = { enabled ->
                                        onDraftChange {
                                            if (!enabled) {
                                                it.copy(serviceDays = null, serviceWeekdays = emptySet())
                                            } else {
                                                it.copy(
                                                    serviceDays = it.serviceDays ?: 30,
                                                    serviceWeekdays = it.serviceWeekdays.ifEmpty {
                                                        setOf(
                                                            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                                                            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = draft.serviceDays != null, onCheckedChange = null, enabled = !state.saving)
                            Text("按累计服务天数计算到期")
                        }
                        if (draft.serviceDays != null) {
                            OutlinedTextField(
                                value = draft.serviceDays.toString(),
                                onValueChange = { value ->
                                    val days = value.trim().toIntOrNull()
                                    onDraftChange { current ->
                                        current.copy(
                                            serviceDays = days,
                                            endDate = if (days != null && days >= 1 && current.startDate != null &&
                                                current.serviceWeekdays.isNotEmpty()
                                            ) {
                                                GameCalendar.hostingEndDateForWeekdays(
                                                    current.startDate, days, current.serviceWeekdays,
                                                )
                                            } else {
                                                current.endDate
                                            },
                                        )
                                    }
                                },
                                label = { Text("累计服务天数") },
                                singleLine = true,
                                enabled = !state.saving,
                                isError = errors["serviceDays"] != null,
                                supportingText = { Text(errors["serviceDays"] ?: "1–3650，只统计所选服务星期") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            ) {
                                DayOfWeek.entries.forEach { day ->
                                    FilterChip(
                                        selected = day in draft.serviceWeekdays,
                                        enabled = !state.saving,
                                        onClick = {
                                            onDraftChange { current ->
                                                val next = if (day in current.serviceWeekdays) {
                                                    current.serviceWeekdays - day
                                                } else {
                                                    current.serviceWeekdays + day
                                                }
                                                current.copy(
                                                    serviceWeekdays = next,
                                                    endDate = if (current.serviceDays != null && current.startDate != null &&
                                                        next.isNotEmpty()
                                                    ) {
                                                        GameCalendar.hostingEndDateForWeekdays(
                                                            current.startDate, current.serviceDays, next,
                                                        )
                                                    } else {
                                                        current.endDate
                                                    },
                                                )
                                            }
                                        },
                                        label = { Text(weekdayLabel(day)) },
                                    )
                                }
                            }
                            if (errors["serviceWeekdays"] != null) {
                                Text(
                                    checkNotNull(errors["serviceWeekdays"]),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            item {
                EditorSection(title = if (hosting) "本期约定价格" else "约定总价") {
                    Text(
                        "只更正约定价格；已有收款流水原样保留，不会自动改写或删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = draft.priceYuan,
                        onValueChange = { value -> onDraftChange { it.copy(priceYuan = value) } },
                        label = { Text("金额（元）") },
                        singleLine = true,
                        enabled = !state.saving,
                        isError = errors["price"] != null,
                        supportingText = { Text(errors["price"] ?: priceHint(detail, draft, hosting)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.priceReason,
                        onValueChange = { value -> onDraftChange { it.copy(priceReason = value) } },
                        label = { Text("改价原因（选填，写入修改记录）") },
                        singleLine = true,
                        enabled = !state.saving,
                        isError = errors["priceReason"] != null,
                        supportingText = { Text(errors["priceReason"] ?: "修改记录会保留旧价与新价，便于对账追溯") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                EditorSection(title = "收款方式") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaymentMode.entries.forEach { mode ->
                            FilterChip(
                                selected = draft.paymentMode == mode,
                                enabled = !state.saving,
                                onClick = { onDraftChange { it.copy(paymentMode = mode) } },
                                label = { Text(if (mode == PaymentMode.ONE_TIME) "一次结清" else "分批收款") },
                            )
                        }
                    }
                    Text(
                        "只影响后续结算方式，不改动已有收款流水。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                EditorSection(title = "刷新规则") {
                    Text(
                        "只影响当前期与未来期；已结束期次保留原规则，避免改写历史打卡日期。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TimeZoneSelector(
                        value = draft.zoneId,
                        onValue = { zone -> onDraftChange { it.copy(zoneId = zone) } },
                        error = errors["zone"],
                    )
                    OutlinedTextField(
                        value = draft.dailyRefreshText,
                        onValueChange = { value -> onDraftChange { it.copy(dailyRefreshText = value) } },
                        label = { Text("每日刷新时间 HH:MM") },
                        singleLine = true,
                        enabled = !state.saving,
                        isError = errors["daily"] != null,
                        supportingText = { errors["daily"]?.let { message -> Text(message) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.weeklyRefreshDayText,
                            onValueChange = { value -> onDraftChange { it.copy(weeklyRefreshDayText = value) } },
                            label = { Text("每周刷新星期 1–7") },
                            singleLine = true,
                            enabled = !state.saving,
                            isError = errors["weeklyDay"] != null,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = draft.weeklyRefreshTimeText,
                            onValueChange = { value -> onDraftChange { it.copy(weeklyRefreshTimeText = value) } },
                            label = { Text("每周刷新 HH:MM") },
                            singleLine = true,
                            enabled = !state.saving,
                            isError = errors["weekly"] != null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    errors["weeklyDay"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    errors["weekly"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedTextField(
                        value = draft.monthlyRefreshDaysText,
                        onValueChange = { value ->
                            onDraftChange { it.copy(monthlyRefreshDaysText = value) }
                        },
                        label = { Text("每月刷新日（如 1,15；留空按周）") },
                        singleLine = true,
                        enabled = !state.saving,
                        isError = errors["monthlyRefresh"] != null,
                        supportingText = {
                            Text(
                                errors["monthlyRefresh"]
                                    ?: "设置后每周任务按「刷新到刷新」的月度窗口计算；该月没有的日号自动跳过。",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                EditorSection(title = "任务") {
                    if (historyLocked) {
                        // Ended periods keep their task definitions as history; showing the
                        // list read-only makes that restriction explicit (the repository
                        // rejects a task rewrite for such a period anyway).
                        Text(
                            "该期次已结束，任务定义作为历史保留，不能新增、修改或移除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        draft.tasks.forEach { task ->
                            Text("· ${task.title.ifBlank { "（未命名任务）" }}", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                    Text(
                        if (countBased) {
                            "任务改动从当前轮次起生效，已完成轮次保留；名称、单位和目标的更正保留任务身份与已记录进度。"
                        } else if (hosting) {
                            "只影响所选期次；名称、单位和目标的更正保留任务身份与已记录进度，" +
                                "改变重复规则或进度类型会从当前游戏日起建立新任务，移除任务只停止后续生成。"
                        } else {
                            "名称、单位和目标的更正保留任务身份与已记录进度；移除任务不会删除已有截图与完成记录。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    errors["tasks"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    draft.tasks.forEachIndexed { index, task ->
                        key(index) {
                            TaskEditor(
                                index = index,
                                task = task,
                                hosting = hosting,
                                onChange = { transform ->
                                    onDraftChange { current ->
                                        current.copy(
                                            tasks = current.tasks.toMutableList().also { it[index] = transform(task) },
                                        )
                                    }
                                },
                                onRemove = {
                                    onDraftChange { current ->
                                        current.copy(tasks = current.tasks.filterIndexed { i, _ -> i != index })
                                    }
                                },
                                canRemove = !state.saving && draft.tasks.size > 1,
                            )
                        }
                        errors["task.$index"]?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        enabled = !state.saving && draft.tasks.size < 30,
                        onClick = {
                            onDraftChange { current ->
                                current.copy(tasks = current.tasks + TaskDraft(periodId = current.periodId))
                            }
                        },
                    ) { Text("添加任务") }
                    Text("至少保留一项任务。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Surface(shadowElevation = 8.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                state.saveError?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !state.saving, onClick = onCancel) { Text("取消") }
                    Box(modifier = Modifier.weight(1f))
                    Button(enabled = saveEnabled, onClick = onSave) {
                        Text(if (state.saving) "正在保存…" else "保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun reasonForLockedIdentity(detail: OrderDetail): String = when {
    detail.order.cancelled -> "订单已作废：历史归属必须保留。"
    detail.order.archived -> "订单已归档，请先恢复订单。"
    detail.owner.archived || detail.account.archived -> "老板或账号已归档，请先恢复后再操作。"
    detail.order.hostingMode == HostingMode.COUNT_BASED ->
        "打卡式托管的轮次属于该订单与本期价格，移动账号或改变类型会丢失进度；如需更换请作废后重新建单。"
    else ->
        "该订单已有收款（含已作废流水）、打卡、截图、续期或价格修改记录，" +
            "移动账号或改变类型会让历史款项和工作记录归属错误。其他字段仍可在此修改。"
}

private fun priceHint(detail: OrderDetail, draft: OrderEditDraft, hosting: Boolean): String {
    val paid = if (hosting) {
        detail.periods.firstOrNull { it.id == draft.periodId }
            ?.let { period ->
                detail.receipts.filter { it.effective && it.periodId == period.id }.sumOf { it.amountCents }
            } ?: 0L
    } else {
        detail.receipts.filter { it.effective }.sumOf { it.amountCents }
    }
    val newCents = draft.priceCentsOrNull
    val resulting = when {
        newCents == null -> "金额需为不超过两位小数的非负数字"
        newCents >= paid -> "改价后待收 ${Money.formatCents(newCents - paid)}"
        else -> "已收超过新价格 ${Money.formatCents(paid - newCents)}（多收）"
    }
    return "已收 ${Money.formatCents(paid)}。$resulting"
}

private fun weekdayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
}
