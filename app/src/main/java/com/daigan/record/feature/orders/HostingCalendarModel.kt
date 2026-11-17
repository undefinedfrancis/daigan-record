package com.daigan.record.feature.orders

import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.data.model.HostingPeriod
import com.daigan.record.data.model.OccurrenceRules
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.TaskOccurrence
import java.time.LocalDate
import java.time.YearMonth

enum class HostingCalendarDayStatus { NONE, OPEN, PARTIAL, COMPLETE }

data class HostingCalendarTask(
    val task: OrderTask,
    val occurrenceKey: String,
    val occurrence: TaskOccurrence?,
) {
    val completed: Boolean get() = occurrence?.completed == true
}

data class HostingCalendarDay(
    val date: LocalDate,
    val inPeriod: Boolean,
    val isToday: Boolean,
    val tasks: List<HostingCalendarTask>,
    val status: HostingCalendarDayStatus,
)

data class HostingCalendarMonth(
    val month: YearMonth,
    val days: List<HostingCalendarDay>,
    val completedDayCount: Int,
)

/** Calendar status is derived from the saved period rules and existing occurrence rows. */
fun buildHostingCalendarMonth(
    period: HostingPeriod,
    tasks: List<OrderTask>,
    occurrences: List<TaskOccurrence>,
    month: YearMonth,
    today: LocalDate,
): HostingCalendarMonth {
    val rules = GameRefreshRules(
        zoneId = period.refresh.zoneId,
        dailyRefresh = period.refresh.dailyRefresh,
        weeklyRefreshDay = period.refresh.weeklyRefreshDay,
        weeklyRefreshTime = period.refresh.weeklyRefreshTime,
        monthlyRefreshDays = period.refresh.monthlyRefreshDays,
    )
    val periodTasks = tasks.filter { it.periodId == period.id }.sortedBy { it.sortOrder }
    val occurrenceMap = occurrences.associateBy { it.taskDefinitionId to it.occurrenceKey }
    val occurrencesByDate = occurrences.groupBy { it.taskDefinitionId to it.attributedDate }

    fun day(date: LocalDate): HostingCalendarDay {
        val inside = date in period.startDate..period.endDate
        val scheduled = if (!inside) emptyList() else periodTasks.flatMap { task ->
            OccurrenceRules.slotsForDate(
                recurrence = task.recurrence,
                weekdays = task.weekdays,
                everyNDays = task.everyNDays,
                monthDay = task.monthDay,
                monthDays = task.monthDays,
                activeFrom = task.activeFrom,
                activeUntil = task.activeUntil,
                retiredAt = task.retiredAt,
                serviceWeekdays = period.serviceWeekdays,
                periodStart = period.startDate,
                periodEnd = period.endDate,
                date = date,
                rules = rules,
            )
                // A weekly window can overlap multiple game dates. Its single saved
                // attribution date is the only date that owns the row in this calendar.
                .filter { slot ->
                    (occurrenceMap[task.id to slot.key]?.attributedDate ?: slot.attributedDate) == date
                }
                .map { slot ->
                    HostingCalendarTask(
                        task = task,
                        occurrenceKey = slot.key,
                        occurrence = occurrenceMap[task.id to slot.key],
                    )
                }
        }
        // Persisted rows own their original attribution even when a later service
        // mask or task-definition change no longer generates that historical slot.
        val saved = if (!inside) emptyList() else periodTasks.flatMap { task ->
            occurrencesByDate[task.id to date].orEmpty()
                .map { HostingCalendarTask(task, it.occurrenceKey, it) }
        }
        val visible = (scheduled + saved).distinctBy { it.task.id to it.occurrenceKey }
        val completeCount = visible.count { it.completed }
        val status = when {
            visible.isEmpty() -> HostingCalendarDayStatus.NONE
            completeCount == visible.size -> HostingCalendarDayStatus.COMPLETE
            completeCount > 0 -> HostingCalendarDayStatus.PARTIAL
            else -> HostingCalendarDayStatus.OPEN
        }
        return HostingCalendarDay(date, inside, date == today, visible, status)
    }

    val days = (1..month.lengthOfMonth()).map { day(month.atDay(it)) }
    val periodDays = generateSequence(period.startDate) { previous ->
        previous.plusDays(1).takeIf { it <= period.endDate }
    }
    val completedDayCount = periodDays.count { day(it).status == HostingCalendarDayStatus.COMPLETE }
    return HostingCalendarMonth(month, days, completedDayCount)
}
