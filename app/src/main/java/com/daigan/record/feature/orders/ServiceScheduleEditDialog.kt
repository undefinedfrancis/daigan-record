package com.daigan.record.feature.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.data.model.OrderDetail
import java.time.DayOfWeek

/** Input remains mounted after submit; the owning screen dismisses only after success. */
@Composable
internal fun ServiceScheduleEditDialog(
    detail: OrderDetail,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Int?, Set<DayOfWeek>) -> Unit,
) {
    val eligiblePeriods = detail.periods.filter { period ->
        val rules = GameRefreshRules(period.refresh.zoneId, period.refresh.dailyRefresh,
            period.refresh.weeklyRefreshDay, period.refresh.weeklyRefreshTime)
        !period.endDate.isBefore(GameCalendar.gameDate(detail.now, rules))
    }.sortedBy { it.periodIndex }
    var selectedId by rememberSaveable(detail.order.id) {
        mutableStateOf(eligiblePeriods.firstOrNull { it.id == detail.effectivePeriod?.id }?.id
            ?: eligiblePeriods.firstOrNull()?.id)
    }
    // Saveable primitive maps preserve both incomplete input and drafts when switching
    // period, rotating the phone or receiving an unchanged detail after a failed save.
    var cumulative by rememberSaveable(detail.order.id) {
        mutableStateOf(detail.periods.associate { it.id to (it.serviceDays != null) })
    }
    var dayTexts by rememberSaveable(detail.order.id) {
        mutableStateOf(detail.periods.associate { it.id to (it.serviceDays ?: it.durationDays).toString() })
    }
    var weekdayBits by rememberSaveable(detail.order.id) {
        mutableStateOf(detail.periods.associate { period ->
            period.id to (if (period.serviceDays == null) 127 else
                period.serviceWeekdays.fold(0) { bits, day -> bits or (1 shl (day.value - 1)) })
        })
    }
    val period = eligiblePeriods.firstOrNull { it.id == selectedId }
    val isCumulative = cumulative[selectedId] == true
    val daysText = dayTexts[selectedId].orEmpty()
    val days = daysText.toIntOrNull()?.takeIf { it in 1..3650 }
    val mask = weekdayBits[selectedId] ?: 0
    val weekdays = DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
    val preview = if (period != null && isCumulative && days != null && weekdays.isNotEmpty()) {
        runCatching { GameCalendar.hostingEndDateForWeekdays(period.startDate, days, weekdays) }.getOrNull()
    } else null
    val editable = detail.isHosting && !detail.order.cancelled && !detail.owner.archived && !detail.account.archived
    val valid = editable && period != null && (!isCumulative || preview != null)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修改服务计划") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("选择当前期或未来期。只修改所选期次的服务计划，其他期次与已有收款保持原样。")
                eligiblePeriods.forEach { item ->
                    val rules = GameRefreshRules(item.refresh.zoneId, item.refresh.dailyRefresh,
                        item.refresh.weeklyRefreshDay, item.refresh.weeklyRefreshTime)
                    val label = if (item.startDate.isAfter(GameCalendar.gameDate(detail.now, rules))) "未来期" else "当前期"
                    FilterChip(
                        selected = selectedId == item.id,
                        enabled = !busy,
                        onClick = { selectedId = item.id },
                        label = { Text("$label · 第 ${item.periodIndex + 1} 期") },
                    )
                    Text("${item.startDate} 至 ${item.endDate}", style = MaterialTheme.typography.bodySmall)
                }
                if (period == null) {
                    Text("没有可修改的当前或未来期次；已结束期次的服务计划保留。")
                } else {
                    FilterChip(selected = !isCumulative, enabled = !busy && editable,
                        onClick = { cumulative = cumulative + (period.id to false) },
                        label = { Text("连续日期") })
                    FilterChip(selected = isCumulative, enabled = !busy && editable,
                        onClick = { cumulative = cumulative + (period.id to true) },
                        label = { Text("累计服务日") })
                    if (isCumulative) {
                        Text("按所选星期计算计划服务日；填写从本期开始日起的总天数，并非剩余天数。不会按实际打卡扣天，也不自动考虑法定节假日或调休。")
                        OutlinedTextField(
                            value = daysText,
                            onValueChange = { dayTexts = dayTexts + (period.id to it) },
                            label = { Text("本期计划服务总天数") },
                            supportingText = { Text("请输入 1–3650 天") },
                            singleLine = true,
                            enabled = !busy && editable,
                            isError = days == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().testTag("service-schedule-days"),
                        )
                        Text("选择服务星期（至少一项）")
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(selected = day in weekdays, enabled = !busy && editable,
                                onClick = { weekdayBits = weekdayBits + (period.id to (mask xor (1 shl (day.value - 1)))) },
                                label = { Text(listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day.value - 1]) })
                        }
                        if (weekdays.isEmpty()) Text("至少选择一个服务星期", color = MaterialTheme.colorScheme.error)
                        Text("开始日期：${period.startDate}；预计结束：${preview ?: "请完善天数与星期"}")
                    } else {
                        Text("连续日期模式保留本期现有日期：${period.startDate} 至 ${period.endDate}。需要更正日期时，请使用“修改日期”。")
                    }
                    Text("已有完成记录的日期受到保护；排除这些日期或与其他期次重叠的计划会被拒绝。保存失败时输入会保留，可继续调整。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(enabled = valid && !busy, onClick = {
                period?.let { onSave(it.id, if (isCumulative) days else null,
                    if (isCumulative) weekdays else emptySet()) }
            }) { Text(if (busy) "正在保存…" else "保存服务计划") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}
