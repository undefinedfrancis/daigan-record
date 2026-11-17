package com.daigan.record.feature.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.OwnerSummary
import java.time.LocalDate

/** Only unused orders may change customer account or order type; history stays in place. */
@Composable
internal fun OrderIdentityEditDialog(
    detail: OrderDetail,
    owners: List<OwnerSummary>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, OrderType, LocalDate, LocalDate?) -> Unit,
) {
    val historyExists = detail.receipts.isNotEmpty() || detail.occurrences.isNotEmpty() ||
        detail.attachments.isNotEmpty() || detail.priceChanges.isNotEmpty() || detail.periods.size > 1 ||
        detail.order.completed || detail.tasks.any { it.completed || it.currentValue.compareTo(it.startValue) != 0 }
    val editable = !historyExists && !detail.order.cancelled && !detail.owner.archived && !detail.account.archived
    val options = owners.filter { !it.owner.archived }.flatMap { summary ->
        summary.accounts.filter { !it.archived && it.ownerId == summary.owner.id }.map { account ->
            account.id to "${summary.owner.displayNumber} · ${summary.owner.nickname} · ${account.gameName} · ${account.server} · UID ${account.uid}"
        }
    }.distinctBy { it.first }
    var accountId by rememberSaveable(detail.order.id) { mutableStateOf(detail.account.id) }
    var typeName by rememberSaveable(detail.order.id) { mutableStateOf(detail.order.type.name) }
    val period = detail.periods.singleOrNull()
    var startText by rememberSaveable(detail.order.id) {
        mutableStateOf((period?.startDate ?: detail.today).toString())
    }
    var endText by rememberSaveable(detail.order.id) {
        mutableStateOf((period?.endDate ?: detail.order.deadlineDate ?: detail.today.plusDays(29)).toString())
    }
    var hasDeadline by rememberSaveable(detail.order.id) { mutableStateOf(detail.order.deadlineDate != null) }
    val hosting = typeName == OrderType.HOSTING.name
    val start = LocalDate.parse(startText)
    val end = LocalDate.parse(endText)
    val validDates = !hosting || !end.isBefore(start)
    val valid = editable && options.any { it.first == accountId } && validDates
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("更正所属账号与订单类型") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (historyExists) {
                    Text("此订单已有历史，不能移动账号或更改类型。", color = MaterialTheme.colorScheme.error)
                    Text("已有收款（包括已作废流水）、打卡、截图、任务完成或进度、续期或价格修改记录的订单，必须保留原账号与类型，避免历史款项和工作记录被错误归属。")
                    Text("任务内容、收款及其他可更正字段，仍可通过各自的修改入口处理。")
                } else if (!editable) {
                    Text("订单已作废或所属老板、账号已归档，请先返回查看订单状态。")
                } else {
                    Text("仅适用于未开始使用的误建订单。约定价格与任务身份保留；更正所属账号后，订单归属于所选老板的账号。")
                    Text("选择所属账号", style = MaterialTheme.typography.titleSmall)
                    if (options.isEmpty()) Text("暂无可用账号，请先在老板资料中添加或恢复账号。")
                    options.forEach { (id, label) ->
                        FilterChip(selected = accountId == id, enabled = !busy,
                            onClick = { accountId = id }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                    }
                    Text("订单类型", style = MaterialTheme.typography.titleSmall)
                    FilterChip(selected = hosting, enabled = !busy,
                        onClick = { typeName = OrderType.HOSTING.name }, label = { Text("托管订单") })
                    FilterChip(selected = !hosting, enabled = !busy,
                        onClick = { typeName = OrderType.SHORT_TASK.name }, label = { Text("短期订单") })
                    if (hosting) {
                        Text("短期单改为托管时，原一次性任务改为每日任务，其他重复规则保留。请明确本期开始与结束日期。")
                        if (busy) Text("服务日期：$start 至 $end") else {
                            DateField("开始日期", start, { startText = it.toString() })
                            DateField("结束日期", end, { endText = it.toString() })
                        }
                        if (!validDates) Text("结束日期不能早于开始日期", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("托管改为短期单后停止按日、周等周期生成任务，每项任务仅需完成一次。")
                        Row(Modifier.fillMaxWidth().toggleable(value = hasDeadline, enabled = !busy,
                            role = Role.Checkbox, onValueChange = { hasDeadline = it }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = hasDeadline, onCheckedChange = null, enabled = !busy)
                            Text("设置截止日期")
                        }
                        if (hasDeadline) {
                            if (busy) Text("截止日期：$end") else {
                                DateField("截止日期", end, { endText = it.toString() })
                            }
                        } else Text("不设置截止日期，不会因日期到期自动隐藏。")
                    }
                    Text("提交后仍会重新检查是否已有记录；检查失败会保留输入，不会移动历史收款或记录。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(enabled = valid && !busy, onClick = {
                onSave(accountId, OrderType.valueOf(typeName), start,
                    if (hosting || hasDeadline) end else null)
            }) { Text(if (busy) "正在保存…" else "保存账号与类型") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
