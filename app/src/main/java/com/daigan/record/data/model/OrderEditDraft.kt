package com.daigan.record.data.model

import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The single draft behind the unified order editor (UF-030).
 *
 * One screen edits what used to be six independent dialogs (title, notes, price, dates,
 * refresh, tasks) plus the payment mode and — for still-unused orders — the owning
 * account/order type. The draft is the *only* input of
 * `OrderRepository.applyOrderEdit`, which validates and commits every field in one
 * transaction: a rejected field writes nothing, so the user can correct the input and
 * press save again instead of living with a half-applied edit.
 *
 * Fields that do not apply to the order's mode are kept but ignored, exactly like
 * `HostingOrderDraft` does for count-based hosting:
 *
 * * date-based hosting → [periodId], [startDate], [endDate], [serviceDays],
 *   [serviceWeekdays] and a per-period price;
 * * 打卡式托管 (count-based) → [targetSessions] and a per-period price, never dates;
 * * short order → [deadlineDate] and the whole-order price.
 */
data class OrderEditDraft(
    val orderId: String,
    /** Target account; only usable while the order has no history. */
    val accountId: String,
    /** Target type; only usable while the order has no history. */
    val type: OrderType,
    val title: String,
    val notes: String,
    val paymentMode: PaymentMode,
    /** The hosting period being edited (its container row for count-based hosting). */
    val periodId: String?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val deadlineDate: LocalDate?,
    val priceYuan: String,
    /** Written into the price-correction audit row; never rewrites receipts. */
    val priceReason: String,
    /**
     * Refresh rules stay textual while editing: a half-typed time must not be silently
     * replaced by a default when another field changes or a save fails.
     */
    val zoneId: String,
    val dailyRefreshText: String,
    val weeklyRefreshDayText: String,
    val weeklyRefreshTimeText: String,
    /** 每月刷新日（逗号分隔 1–31）；空白表示按周刷新。 */
    val monthlyRefreshDaysText: String = "",
    /** Cumulative service-day plan of the selected period; null = every calendar day. */
    val serviceDays: Int?,
    val serviceWeekdays: Set<DayOfWeek>,
    /** Agreed rounds of a count-based order; ignored by every other mode. */
    val targetSessions: Int?,
    val tasks: List<TaskDraft>,
    /**
     * Identity of this edit command. A retry after a rejected/failed save reuses it, so
     * the price audit row can never be written twice for one user action.
     */
    val operationId: String = UUID.randomUUID().toString(),
) {
    val priceCentsOrNull: Long? get() = Money.parseYuanToCentsOrNull(priceYuan)

    /** Parsed refresh snapshot, or null when the time/zone text is not valid yet. */
    val refreshOrNull: GameRefreshRules?
        get() = runCatching {
            GameRefreshRules.fromMinutes(
                zoneId = java.time.ZoneId.of(zoneId.trim()),
                dailyRefreshMinutes = parseClockMinutes(dailyRefreshText) ?: error("daily"),
                weeklyRefreshDayIso = weeklyRefreshDayText.trim().toInt().takeIf { it in 1..7 } ?: error("day"),
                weeklyRefreshMinutes = parseClockMinutes(weeklyRefreshTimeText) ?: error("weekly"),
                monthlyRefreshDays = parsedMonthlyRefreshDays() ?: error("monthly"),
            )
        }.getOrNull()

    /** Null only when the text is invalid; blank means "weekly refresh". */
    fun parsedMonthlyRefreshDays(): Set<Int>? =
        if (monthlyRefreshDaysText.isBlank()) emptySet() else parseMonthlyDates(monthlyRefreshDaysText)

    private fun parseClockMinutes(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /**
     * Editor-side validation. The repository re-checks everything inside the write
     * transaction; this only makes an error visible before a round trip.
     *
     * Task rules and the service-day/weekday combination come from the same
     * `HostingOrderDraft` / `ShortOrderDraft` validators used when the order was created,
     * so the editor cannot accept a task set the repository would reject.
     */
    fun validate(detail: OrderDetail): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            errors["title"] = "请填写订单名称"
        } else if (ShortOrderDraft.titleExceedsLimit(trimmedTitle)) {
            errors["title"] = "订单名称最多 ${ShortOrderDraft.MAX_TITLE_LENGTH} 个字符"
        }
        // The order-notes entry point allows a longer text than a creation draft, so an
        // existing long note stays editable instead of blocking unrelated corrections.
        if (notes.trim().length > NOTES_MAX_LENGTH) {
            errors["notes"] = "备注最多 $NOTES_MAX_LENGTH 个字符"
        }
        if (priceYuan.isBlank()) {
            errors["price"] = "请填写约定价格"
        } else if (priceCentsOrNull == null) {
            errors["price"] = "金额需为不超过两位小数的非负数字"
        }
        if (priceReason.trim().length > RECEIPT_REASON_MAX_LENGTH) {
            errors["priceReason"] = "修改原因最多 $RECEIPT_REASON_MAX_LENGTH 个字符"
        }
        if (operationId.isBlank()) {
            errors["operationId"] = "缺少修改操作标识，请重新打开编辑页"
        }
        runCatching { java.time.ZoneId.of(zoneId.trim()) }.onFailure {
            errors["zone"] = "时区格式不正确，例如 Asia/Shanghai"
        }
        if (parseClockMinutes(dailyRefreshText) == null) {
            errors["daily"] = "每日刷新时间需为 HH:MM"
        }
        if (weeklyRefreshDayText.trim().toIntOrNull() !in 1..7) {
            errors["weeklyDay"] = "每周刷新星期需为 1–7"
        }
        if (parseClockMinutes(weeklyRefreshTimeText) == null) {
            errors["weekly"] = "每周刷新时间需为 HH:MM"
        }
        if (parsedMonthlyRefreshDays() == null) {
            errors["monthlyRefresh"] = "每月刷新日需为 1–31，可用逗号分隔多个日期"
        }

        val hosting = type == OrderType.HOSTING
        val countBased = hosting && detail.order.hostingMode == HostingMode.COUNT_BASED

        // Which groups of fields the user actually touched. An untouched group is not
        // re-validated: legacy rows (an ended period, an unusual plan) must still allow a
        // name/price correction without having to "fix" their history first.
        val storedPeriod = detail.periods.firstOrNull { it.id == periodId }
        val storedTasks = detail.tasks
            .filter { it.retiredAt == null && it.periodId == periodId }
            .sortedBy { it.sortOrder }
            .map { it.toEditDraft() }
        val tasksChanged = when {
            storedTasks.isEmpty() -> tasks.any { it.id != null || it.title.isNotBlank() }
            tasks.size != storedTasks.size -> true
            else -> tasks != storedTasks
        }
        val datesChanged = startDate != storedPeriod?.startDate || endDate != storedPeriod?.endDate
        val planChanged = if (serviceDays == null && storedPeriod?.serviceDays == null) {
            false
        } else {
            serviceDays != storedPeriod?.serviceDays ||
                serviceWeekdays != storedPeriod?.serviceWeekdays.orEmpty()
        }
        val hostingFieldsChanged = datesChanged || planChanged || storedPeriod == null

        if (countBased) {
            val target = targetSessions
            when {
                target == null || target < 1 -> errors["targetSessions"] = "请填写大于 0 的打卡次数"
                target > CountHosting.MAX_TARGET_SESSIONS ->
                    errors["targetSessions"] = "打卡次数最多 ${CountHosting.MAX_TARGET_SESSIONS} 次"
                target < detail.completedSessionCount ->
                    errors["targetSessions"] = "已完成 ${detail.completedSessionCount} 次打卡，约定次数不能少于已完成次数"
            }
        }

        if (hosting && !countBased && hostingFieldsChanged) {
            val start = startDate
            val end = endDate
            if (start == null) errors["start"] = "请选择开始日期"
            if (end == null) errors["end"] = "请选择结束日期"
            if (start != null && end != null) {
                if (end.isBefore(start)) {
                    errors["dates"] = "结束日期不能早于开始日期"
                } else if (serviceDays == null && ChronoUnit.DAYS.between(start, end) >= HostingOrderDraft.MAX_DURATION_DAYS) {
                    errors["dates"] = "托管天数最多 ${HostingOrderDraft.MAX_DURATION_DAYS} 天"
                }
            }
            val days = serviceDays
            if (days != null) {
                if (days < 1 || days > HostingOrderDraft.MAX_DURATION_DAYS) {
                    errors["serviceDays"] = "累计服务天数需为 1–${HostingOrderDraft.MAX_DURATION_DAYS} 的整数"
                }
                if (serviceWeekdays.isEmpty()) {
                    errors["serviceWeekdays"] = "累计服务期需至少选择一个服务星期"
                }
                if (start != null && days in 1..HostingOrderDraft.MAX_DURATION_DAYS && serviceWeekdays.isNotEmpty()) {
                    val derivedEnd = GameCalendar.hostingEndDateForWeekdays(start, days, serviceWeekdays)
                    if (end != null && end != derivedEnd) {
                        errors["dates"] = "累计服务期由开始日期与服务星期推算，结束日期应为 $derivedEnd"
                    }
                }
            }
        }

        // Reuse the creation validators for tasks and cross-field rules. The price is a
        // placeholder: the real amount is validated above and audited by the repository.
        val parsedRefresh = refreshOrNull ?: GameRefreshRules.provisional()
        val creationErrors = if (hosting) {
            HostingOrderDraft(
                ownerId = detail.owner.id,
                accountId = accountId,
                title = title,
                startDate = if (countBased) detail.today else (startDate ?: detail.today),
                endDate = if (countBased) detail.today else (endDate ?: detail.today),
                priceYuan = "1",
                refresh = parsedRefresh,
                tasks = tasks,
                serviceDays = if (countBased) null else serviceDays,
                serviceWeekdays = serviceWeekdays,
                hostingMode = detail.order.hostingMode,
                targetSessions = targetSessions,
            ).validate()
        } else {
            ShortOrderDraft(
                ownerId = detail.owner.id,
                accountId = accountId,
                title = title,
                priceYuan = "1",
                deadlineDate = deadlineDate,
                notes = notes,
                tasks = tasks,
                refresh = parsedRefresh,
            ).validate()
        }
        creationErrors.forEach { (key, message) ->
            // Keep the more specific editor message for keys already explained above,
            // and never block a save over a group the user did not change.
            if (key in errors) return@forEach
            val taskKey = key == "tasks" || key.startsWith("task")
            val hostingKey = key == "start" || key == "end" || key == "dates" ||
                key == "serviceDays" || key == "serviceWeekdays" || key == "weekdays"
            when {
                taskKey && !tasksChanged -> Unit
                hostingKey && !hostingFieldsChanged -> Unit
                else -> errors[key] = message
            }
        }
        return errors
    }

    companion object {
        /** Matches `OrderRepository.updateOrderNotes`, the entry point this edit uses. */
        const val NOTES_MAX_LENGTH = 1000

        /** Prefills the editor from exactly what the detail page is showing. */
        fun of(detail: OrderDetail, today: LocalDate = detail.today): OrderEditDraft {
            val period = when {
                detail.countBased -> detail.periods.minByOrNull { it.periodIndex }
                detail.isHosting -> detail.currentPeriod
                    ?: detail.latestPeriod
                    ?: detail.periods.minByOrNull { it.periodIndex }
                else -> null
            }
            return OrderEditDraft(
                orderId = detail.order.id,
                accountId = detail.account.id,
                type = detail.order.type,
                title = detail.order.title,
                notes = detail.order.notes.orEmpty(),
                paymentMode = detail.order.paymentMode,
                periodId = period?.id,
                startDate = period?.startDate ?: today,
                endDate = period?.endDate ?: detail.order.deadlineDate ?: today.plusDays(29),
                deadlineDate = detail.order.deadlineDate,
                priceYuan = (if (detail.isHosting) period?.priceCents else detail.order.priceCents)
                    ?.let(Money::centsToYuanText)
                    .orEmpty(),
                priceReason = "",
                zoneId = detail.order.refresh.zoneId.id,
                dailyRefreshText = detail.order.refresh.dailyRefresh.toString(),
                weeklyRefreshDayText = detail.order.refresh.weeklyRefreshDay.value.toString(),
                weeklyRefreshTimeText = detail.order.refresh.weeklyRefreshTime.toString(),
                monthlyRefreshDaysText = detail.order.monthlyRefreshDays.sorted().joinToString(","),
                serviceDays = period?.serviceDays,
                serviceWeekdays = period?.serviceWeekdays.orEmpty(),
                targetSessions = period?.serviceCount ?: detail.order.targetSessions,
                tasks = detail.tasks
                    .filter { it.retiredAt == null && it.periodId == period?.id }
                    .sortedBy { it.sortOrder }
                    .map { it.toEditDraft() }
                    .ifEmpty { listOf(TaskDraft(periodId = period?.id)) },
            )
        }
    }
}

/** Editor form of one existing task, preserving identity and every recurrence field. */
fun OrderTask.toEditDraft(): TaskDraft = TaskDraft(
    title = title,
    progressType = progressType,
    startValue = startValue.toPlainString(),
    targetValue = targetValue.toPlainString(),
    unit = unit.orEmpty(),
    recurrence = recurrence,
    weekdays = weekdays,
    everyNDays = everyNDays?.toString().orEmpty(),
    monthDay = monthDays.ifEmpty { setOfNotNull(monthDay) }.sorted().joinToString(","),
    id = id,
    periodId = periodId,
    numericMode = numericMode,
    activeFrom = activeFrom?.toString().orEmpty(),
    activeUntil = activeUntil?.toString().orEmpty(),
    colorArgb = colorArgb,
)

/**
 * Reloads every period-scoped field of the draft from [periodId]: dates, agreed price,
 * service plan and task definitions. Order-level fields (name, notes, payment mode,
 * account, type, refresh rules) are deliberately untouched.
 *
 * Used by the editor when the user picks a period — including an already ended one, whose
 * price and permitted dates may still be corrected while its tasks and service plan stay
 * read-only. Returns the receiver unchanged when the period does not belong to [detail].
 */
fun OrderEditDraft.withPeriod(detail: OrderDetail, periodId: String): OrderEditDraft {
    val period = detail.periods.firstOrNull { it.id == periodId } ?: return this
    val tasks = detail.tasks
        .filter { it.retiredAt == null && it.periodId == periodId }
        .sortedBy { it.sortOrder }
        .map { it.toEditDraft() }
        .ifEmpty { listOf(TaskDraft(periodId = periodId)) }
    return copy(
        periodId = period.id,
        startDate = period.startDate,
        endDate = period.endDate,
        priceYuan = Money.centsToYuanText(period.priceCents),
        serviceDays = period.serviceDays,
        serviceWeekdays = period.serviceWeekdays,
        // A count period owns its target; switching the edited period must show that
        // period's value rather than the legacy order-level mirror.
        targetSessions = if (detail.countBased) period.serviceCount else targetSessions,
        tasks = tasks,
    )
}
