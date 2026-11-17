package com.daigan.record.feature.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.GameRefreshRulesDomain
import java.time.LocalDate

/** Shared by the detail controls and their confirmation copy; repository rechecks atomically. */
internal fun OrderDetail.canDeleteUnusedOrder(): Boolean =
    !order.cancelled && !order.completed && priceChanges.isEmpty() && periods.size <= 1 && receipts.isEmpty() && attachments.isEmpty() &&
        occurrences.isEmpty() && tasks.all { !it.completed && it.currentValue.compareTo(it.startValue) == 0 }

/**
 * True when the order already owns business history: receipts (including voided rows),
 * check-ins, screenshots, a renewal or a price correction, completed work or progress.
 *
 * Such an order keeps its original account and type, because re-homing it would silently
 * move money and work to another customer. The unified editor shows the reason instead of
 * the selectors, and `updateUnusedOrderIdentity` re-checks it inside the transaction.
 */
internal fun OrderDetail.hasBusinessHistory(): Boolean =
    receipts.isNotEmpty() || occurrences.isNotEmpty() || attachments.isNotEmpty() ||
        priceChanges.isNotEmpty() || periods.size > 1 || order.completed ||
        tasks.any { it.completed || it.currentValue.compareTo(it.startValue) != 0 }



/**
 * The order-management section (UF-030).
 *
 * Only two entries remain for a live order: **修改** opens the single unified editor and
 * **作废订单** keeps its own confirmation. Every former per-field entry (name, notes,
 * price, dates, tasks, refresh, service plan, identity) now lives inside that one editor
 * instead of being flattened here.
 */
@Composable
internal fun OrderMaintenanceActions(
    detail: OrderDetail,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onDeleteCancelled: () -> Unit = {},
    busy: Boolean = false,
    onRestoreArchived: () -> Unit = {},
) {
    var confirmation by rememberSaveable(detail.order.id) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (detail.order.archived) {
            Text("订单已归档 · 不续期", style = MaterialTheme.typography.titleMedium)
            Text("各期价格、欠款、实收和工作记录保留。可继续收款；恢复订单后可更正工作记录或续期。")
            TextButton(onClick = onRestoreArchived, enabled = !busy && !detail.owner.archived && !detail.account.archived) { Text("恢复订单") }
        } else if (detail.order.cancelled) {
            Text("订单已作废", style = MaterialTheme.typography.titleMedium)
            Text("历史打卡、截图和实际收款保留；不再生成任务，不计入待收款和有效接单数。已有收款仍可更正或作废。")
            TextButton(onClick = { confirmation = "purge" }, enabled = !busy) { Text("删除已作废订单") }
        } else {
            Text("订单管理", style = MaterialTheme.typography.titleMedium)
            TextButton(
                onClick = onEdit,
                enabled = !busy && !detail.owner.archived && !detail.account.archived,
            ) { Text("修改") }
            TextButton(onClick = { confirmation = "cancel" }, enabled = !busy) { Text("作废订单") }
        }
    }
    confirmation?.let { action ->
        val purge = action == "purge"
        val balance = detail.balance()
        val paidSum = balance.totalPaidCents
        AlertDialog(
            onDismissRequest = { if (!busy) confirmation = null },
            title = { Text(if (purge) "删除已作废订单？" else "作废订单？") },
            text = {
                Text(
                    if (purge) {
                        "将永久删除该作废订单及其全部关联记录：打卡 ${detail.occurrences.size} 条、" +
                            "收款 ${detail.receipts.count { it.effective }} 笔（有效合计 ${Money.formatCents(paidSum)}，" +
                            "删除后不再计入收入与统计）、截图 ${detail.attachments.size} 张、" +
                            "任务与期次快照。账号与其他订单不受影响。此操作不能撤销。"
                    } else {
                        "作废后停止生成任务，取消剩余待收款，并从有效接单数中移除。历史打卡、截图与实际收款保留，可在账号订单列表中查看。此操作不能撤销。"
                    },
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    confirmation = null
                    if (purge) onDeleteCancelled() else onCancel()
                }) {
                    Text(if (purge) "确认删除" else "确认作废")
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmation = null }) { Text("取消") } },
        )
    }
}

/**
 * Cleanup entry of a mistaken, still-unused order (UF-030 follow-up).
 *
 * It is deliberately **outside** the two-entry management section: deleting a whole order
 * is not an ordinary field correction, and keeping it separate stops it from being tapped
 * next to 修改. The confirmation states what is removed, and the repository re-checks that
 * the order really has no history.
 */
@Composable
internal fun UnusedOrderCleanupRow(
    detail: OrderDetail,
    busy: Boolean,
    onDelete: () -> Unit,
) {
    if (detail.order.cancelled || detail.order.archived || !detail.canDeleteUnusedOrder()) return
    var confirmation by rememberSaveable(detail.order.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        TextButton(enabled = !busy, onClick = { confirmation = true }) { Text("删除误建订单") }
    }
    if (confirmation) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmation = false },
            title = { Text("删除误建订单？") },
            text = { Text("仅可删除没有打卡、进度、截图、收款或续期记录的误建订单。删除后无法恢复。") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    confirmation = false
                    onDelete()
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmation = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun TaskEditDialog(detail: OrderDetail, busy: Boolean, onDismiss: () -> Unit, onSave: (List<TaskDraft>) -> Unit) {
    // Evaluate each snapshot against its own refresh clock, including future renewals.
    // A count-based order owns one container period whose dates are not business terms,
    // so it stays editable regardless of the calendar.
    val eligiblePeriods = if (detail.countBased) {
        detail.periods.sortedBy { it.periodIndex }
    } else {
        detail.periods.filter { period ->
            val rules = com.daigan.record.core.time.GameRefreshRules(
                period.refresh.zoneId, period.refresh.dailyRefresh,
                period.refresh.weeklyRefreshDay, period.refresh.weeklyRefreshTime,
                monthlyRefreshDays = period.refresh.monthlyRefreshDays,
            )
            !period.endDate.isBefore(com.daigan.record.core.time.GameCalendar.gameDate(detail.now, rules))
        }.sortedBy { it.periodIndex }
    }
    var selectedPeriodId by rememberSaveable(detail.order.id) {
        mutableStateOf(eligiblePeriods.firstOrNull { it.id == detail.currentPeriod?.id }?.id
            ?: eligiblePeriods.firstOrNull()?.id)
    }
    // Keep a separate in-memory draft for each period. Failed saves and switching the
    // selector must not reconstruct user input from the repository's old definitions.
    var periodDrafts by remember(detail.order.id) {
        mutableStateOf<Map<String?, List<TaskDraft>>>(
            (if (detail.isHosting) eligiblePeriods.map { it.id } else listOf(null)).associateWith { periodId ->
                detail.tasks.filter { it.retiredAt == null && it.periodId == periodId }
                    .sortedBy { it.sortOrder }.map { it.toDraft() }
                    .ifEmpty { listOf(TaskDraft(periodId = periodId)) }
            },
        )
    }
    val editable = !detail.isHosting || eligiblePeriods.any { it.id == selectedPeriodId }
    val drafts = periodDrafts[selectedPeriodId].orEmpty()
    fun updateDrafts(value: List<TaskDraft>) {
        if (!busy) periodDrafts = periodDrafts + (selectedPeriodId to value)
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修改任务") },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("名称、单位和目标更正保留任务身份与已记录进度；改变重复规则或进度类型会从当前游戏日起建立新任务，原任务及历史仍可查看。移除任务只停止后续生成。")
                if (detail.isHosting && detail.countBased) {
                    Text("打卡式托管没有起止日期；任务改动从当前轮次起生效，已完成的轮次保留。")
                } else if (detail.isHosting) {
                    Text("只影响所选期次，其他期次保持原样。请选择当前期或未来期。")
                    eligiblePeriods.forEach { period ->
                        val label = if (period.id == detail.effectivePeriod?.id) "当前期" else "未来期"
                        FilterChip(selected = selectedPeriodId == period.id, enabled = !busy,
                            onClick = { selectedPeriodId = period.id },
                            label = { Text("$label · 第 ${period.periodIndex + 1} 期") })
                        Text("${period.startDate} 至 ${period.endDate}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!editable) {
                    Text("没有可修改的当前或未来期次；历史任务可在订单记录中查看。")
                } else {
                    drafts.forEachIndexed { index, draft ->
                        key(selectedPeriodId, index) {
                            TaskEditor(index = index, task = draft, hosting = detail.isHosting,
                                onChange = { transform -> updateDrafts(drafts.toMutableList().also { it[index] = transform(draft) }) },
                                onRemove = { if (drafts.size > 1) updateDrafts(drafts.filterIndexed { i, _ -> i != index }) },
                                canRemove = !busy && drafts.size > 1)
                        }
                    }
                    TextButton(enabled = !busy, onClick = {
                        updateDrafts(drafts + TaskDraft(periodId = selectedPeriodId))
                    }) { Text("添加任务") }
                    Text("至少保留一项任务。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(enabled = !busy && editable && drafts.isNotEmpty(), onClick = { onSave(drafts) }) {
            Text(if (busy) "正在保存…" else "保存")
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

private fun OrderTask.toDraft() = TaskDraft(
    id = id, periodId = periodId, title = title, progressType = progressType,
    startValue = startValue.toPlainString(), targetValue = targetValue.toPlainString(), unit = unit.orEmpty(),
    recurrence = recurrence, weekdays = weekdays, everyNDays = everyNDays?.toString().orEmpty(),
    monthDay = (monthDays.ifEmpty { monthDay?.let { setOf(it) }.orEmpty() }).sorted().joinToString(","),
    numericMode = numericMode, activeFrom = activeFrom?.toString().orEmpty(),
    activeUntil = activeUntil?.toString().orEmpty(), colorArgb = colorArgb,
)




/**
 * Price correction with explicit consequences (UF-012): the dialog names the payable
 * item being changed (a hosting period or the whole short order), previews the
 * resulting 待收/多收 against current receipts, and states that receipts stay untouched.
 */
