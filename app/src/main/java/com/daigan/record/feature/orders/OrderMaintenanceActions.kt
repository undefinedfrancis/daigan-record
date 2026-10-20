package com.daigan.record.feature.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import java.time.LocalDate

/** Shared by the detail controls and their confirmation copy; repository rechecks atomically. */
internal fun OrderDetail.canDeleteUnusedOrder(): Boolean =
    !order.cancelled && periods.size <= 1 && receipts.isEmpty() && attachments.isEmpty() &&
        occurrences.isEmpty() && tasks.all { !it.completed && it.currentValue.compareTo(it.startValue) == 0 }

@Composable
internal fun OrderMaintenanceActions(
    detail: OrderDetail,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onRename: () -> Unit = {},
    onEditPrice: () -> Unit = {},
    busy: Boolean = false,
) {
    var confirmation by rememberSaveable(detail.order.id) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (detail.order.cancelled) {
            Text("订单已作废", style = MaterialTheme.typography.titleMedium)
            Text("历史打卡、截图和实际收款保留；不再生成任务，不计入待收款和有效接单数。已有收款仍可更正或作废。")
        } else {
            Text("订单管理", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRename, enabled = !busy && !detail.owner.archived && !detail.account.archived) {
                Text("修改名称")
            }
            TextButton(onClick = onEditPrice, enabled = !busy && !detail.owner.archived && !detail.account.archived) {
                Text("修改价格")
            }
            TextButton(onClick = onEdit, enabled = !busy && !detail.owner.archived && !detail.account.archived) {
                Text("修改日期")
            }
            if (detail.canDeleteUnusedOrder()) {
                TextButton(onClick = { confirmation = "delete" }, enabled = !busy) { Text("删除误建订单") }
            }
            TextButton(onClick = { confirmation = "cancel" }, enabled = !busy) { Text("作废订单") }
        }
    }
    confirmation?.let { action ->
        val delete = action == "delete"
        AlertDialog(
            onDismissRequest = { if (!busy) confirmation = null },
            title = { Text(if (delete) "删除误建订单？" else "作废订单？") },
            text = { Text(if (delete)
                "仅可删除没有打卡、进度、截图、收款或续期记录的误建订单。删除后无法恢复。"
                else "作废后停止生成任务，取消剩余待收款，并从有效接单数中移除。历史打卡、截图与实际收款保留，可在账号订单列表中查看。此操作不能撤销。") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    confirmation = null
                    if (delete) onDelete() else onCancel()
                }) { Text(if (delete) "确认删除" else "确认作废") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmation = null }) { Text("取消") } },
        )
    }
}

@Composable
internal fun OrderDateEditDialog(
    detail: OrderDetail,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String?, LocalDate?, LocalDate?) -> Unit,
) {
    var selectedPeriodId by rememberSaveable(detail.order.id) { mutableStateOf(detail.currentPeriod?.id) }
    val period = detail.periods.firstOrNull { it.id == selectedPeriodId }
    var startText by rememberSaveable(detail.order.id, selectedPeriodId) { mutableStateOf(period?.startDate?.toString()) }
    var endText by rememberSaveable(detail.order.id, selectedPeriodId) { mutableStateOf((period?.endDate ?: detail.order.deadlineDate)?.toString()) }
    val start = startText?.let(LocalDate::parse)
    val end = endText?.let(LocalDate::parse)
    val valid = !detail.isHosting || (start != null && end != null && !end.isBefore(start))
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修改订单日期") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("只更正服务日期，价格、收款与历史记录保留，不创建新期次。")
                if (detail.isHosting) {
                    if (detail.periods.size > 1) detail.periods.forEach { item ->
                        FilterChip(selected = selectedPeriodId == item.id, enabled = !busy,
                            onClick = { selectedPeriodId = item.id },
                            label = { Text("第 ${item.periodIndex + 1} 期 · ${item.startDate} 至 ${item.endDate}") })
                    }
                    Text("已有打卡或进度的期次不能改开始日期；结束日期不能排除历史记录或与其他期次重叠。")
                    DateField("开始日期", start, { startText = it.toString() })
                }
                DateField(if (detail.isHosting) "结束日期" else "截止日期（可清除）", end,
                    { endText = it.toString() }, onClear = if (detail.isHosting) null else ({ endText = null }))
                if (!valid) Text("结束日期不能早于开始日期", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(enabled = valid && !busy, onClick = { onSave(selectedPeriodId, start, end) }) {
            Text(if (busy) "正在保存…" else "保存日期")
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun OrderTitleEditDialog(
    detail: OrderDetail,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var title by rememberSaveable(detail.order.id) { mutableStateOf(detail.order.title) }
    val trimmed = title.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= 40
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修改订单名称") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只修改显示名称；订单身份、服务日期、任务、打卡与收款记录保持不变。")
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("订单名称") },
                    singleLine = true,
                    isError = title.isNotEmpty() && !valid,
                    supportingText = { Text(if (title.isNotEmpty() && !valid) "名称需为 1–40 个字符" else "用于区分同一账号下的不同订单") },
                )
            }
        },
        confirmButton = {
            Button(enabled = valid && !busy, onClick = { onSave(trimmed) }) {
                Text(if (busy) "正在保存…" else "保存名称")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * Price correction with explicit consequences (UF-012): the dialog names the payable
 * item being changed (a hosting period or the whole short order), previews the
 * resulting 待收/多收 against current receipts, and states that receipts stay untouched.
 */
@Composable
internal fun OrderPriceEditDialog(
    detail: OrderDetail,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (periodId: String?, priceYuan: String, reason: String) -> Unit,
) {
    var selectedPeriodId by rememberSaveable(detail.order.id) { mutableStateOf(detail.currentPeriod?.id) }
    val period = detail.periods.firstOrNull { it.id == selectedPeriodId }
    val balance = detail.balance()
    val item = if (detail.isHosting) {
        balance.items.firstOrNull { it.periodId == selectedPeriodId }
    } else {
        balance.items.firstOrNull()
    }
    var priceText by rememberSaveable(detail.order.id, selectedPeriodId) {
        mutableStateOf(item?.priceCents?.let(Money::centsToYuanText).orEmpty())
    }
    var reasonText by rememberSaveable(detail.order.id) { mutableStateOf("") }
    val newCents = if (priceText.isBlank()) null else Money.parseYuanToCentsOrNull(priceText)
    val valid = newCents != null && newCents >= 0
    val paid = item?.paidCents ?: 0L
    val resulting = if (newCents == null) null else if (newCents >= paid) {
        "改价后待收 ${Money.formatCents(newCents - paid)}"
    } else {
        "改价后已收超过新价格 ${Money.formatCents(paid - newCents)}（多收）"
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修改约定价格") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("只更正约定价格；已有收款流水原样保留，不会自动改写或删除。")
                if (detail.isHosting) {
                    if (detail.periods.size > 1) detail.periods.forEach { item ->
                        FilterChip(
                            selected = selectedPeriodId == item.id,
                            enabled = !busy,
                            onClick = { selectedPeriodId = item.id },
                            label = { Text("第 ${item.periodIndex + 1} 期 · ${item.startDate} 至 ${item.endDate}") },
                        )
                    }
                    Text(
                        "当前期次价格 ${period?.priceCents?.let(Money::formatCents) ?: "—"}；修改只影响所选期次。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "当前约定总价 ${detail.order.priceCents?.let(Money::formatCents) ?: "—"}。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("已收 ${Money.formatCents(paid)}。")
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("新价格（元）") },
                    singleLine = true,
                    isError = priceText.isNotBlank() && !valid,
                    supportingText = { Text(resulting ?: "金额需为不超过两位小数的非负数字") },
                )
                OutlinedTextField(
                    value = reasonText,
                    onValueChange = { reasonText = it },
                    label = { Text("修改原因（选填，写入修改记录）") },
                    singleLine = true,
                    supportingText = { Text("修改记录会保留旧价与新价，便于对账追溯。") },
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid && !busy,
                onClick = { onSave(if (detail.isHosting) selectedPeriodId else null, priceText, reasonText) },
            ) { Text(if (busy) "正在保存…" else "确认改价") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
