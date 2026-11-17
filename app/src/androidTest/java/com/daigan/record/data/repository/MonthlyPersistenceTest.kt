package com.daigan.record.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonthlyPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun monthlyFixedDayPersistsAcrossCompletionMissingMonthBackfillAndRenewal() = runBlocking {
        val env = OrderTestEnvironment(context, initialInstant = Instant.parse("2026-01-31T04:00:00Z"))
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val orderId = env.orderRepository.createHostingOrder(
                hostingDraft(
                    owner.id, account.id, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31),
                    tasks = listOf(TaskDraft(title = "每月领取", recurrence = RecurrenceType.MONTHLY, monthDay = "31")),
                ),
            )
            val original = env.orderRepository.observeOrderDetail(orderId).first()!!
            val task = original.tasks.single()
            val period = original.currentPeriod!!
            assertEquals(31, task.monthDay)
            assertEquals(31, env.database.taskDao().findForPeriod(period.id).single().monthDay)
            val january = env.orderRepository.observeTodayHosting().first().single().tasks.single()
            assertEquals("M:2026-01-31", january.slot.key)
            env.orderRepository.setOccurrenceCompleted(task.id, true, january.slot.key)
            env.orderRepository.setOccurrenceCompleted(task.id, true, january.slot.key)
            assertEquals(1, env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.size)

            env.clock.set(Instant.parse("2026-02-28T04:00:00Z"))
            assertTrue(env.orderRepository.observeTodayHosting().first().single().tasks.isEmpty())
            assertTrue(runCatching {
                env.orderRepository.setHistoricalCompletion(orderId, period.id, task.id, LocalDate.of(2026, 2, 28), true)
            }.isFailure)

            env.clock.set(Instant.parse("2026-03-31T04:00:00Z"))
            val march = env.orderRepository.observeTodayHosting().first().single().tasks.single()
            assertEquals("M:2026-03-31", march.slot.key)
            env.orderRepository.setOccurrenceCompleted(task.id, true, march.slot.key)
            env.orderRepository.setHistoricalCompletion(orderId, period.id, task.id, LocalDate.of(2026, 1, 31), false)
            env.orderRepository.setHistoricalCompletion(orderId, period.id, task.id, LocalDate.of(2026, 1, 31), true)
            val saved = env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences
            assertEquals(setOf("M:2026-01-31", "M:2026-03-31"), saved.map { it.occurrenceKey }.toSet())
            assertTrue(saved.single { it.occurrenceKey == "M:2026-01-31" }.backfilled)

            env.clock.set(Instant.parse("2026-04-01T04:00:00Z"))
            val renewal = env.orderRepository.renewHosting(orderId, "monthly-renewal", 31, 10000)
            val renewed = env.orderRepository.observeOrderDetail(orderId).first()!!
            val renewedTask = renewed.tasksForPeriod(renewal.periodId).single()
            assertNotEquals(task.id, renewedTask.id)
            assertEquals(RecurrenceType.MONTHLY, renewedTask.recurrence)
            assertEquals(31, renewedTask.monthDay)
            assertEquals(31, env.database.taskDao().findForPeriod(renewal.periodId).single().monthDay)
            assertEquals(2, renewed.occurrences.size)
        } finally {
            env.close()
        
    @Test
    fun monthlyRefreshDaysDriveWeeklyWindowsAndSurviveEditAndRenewal() = runBlocking {
        val env = OrderTestEnvironment(context, initialInstant = Instant.parse("2026-01-20T04:00:00Z"))
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val rules = com.daigan.record.core.time.GameRefreshRules.fromMinutes(
                zoneId = java.time.ZoneId.of("Asia/Shanghai"),
                dailyRefreshMinutes = 240,
                weeklyRefreshDayIso = 1,
                weeklyRefreshMinutes = 300,
                monthlyRefreshDays = setOf(1, 15),
            )
            val orderId = env.orderRepository.createHostingOrder(
                hostingDraft(
                    owner.id, account.id, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31),
                    tasks = listOf(TaskDraft(title = "每周", recurrence = RecurrenceType.WEEKLY)),
                    rules = rules,
                ),
            )
            val period = env.database.orderDao().findPeriods(orderId).single()
            assertEquals("1,15", period.monthlyRefreshDays)
            assertEquals(setOf(1, 15), env.orderRepository.observeOrderDetail(orderId).first()!!.order.monthlyRefreshDays)

            val today = env.orderRepository.observeTodayHosting().first().single()
            // 2026-01-20 is inside the 01-15 -> 02-01 monthly window, not a 7-day one.
            assertEquals("W:2026-01-15", today.tasks.single().slot.key)

            val updated = com.daigan.record.core.time.GameRefreshRules.fromMinutes(
                zoneId = java.time.ZoneId.of("Asia/Shanghai"),
                dailyRefreshMinutes = 240,
                weeklyRefreshDayIso = 1,
                weeklyRefreshMinutes = 300,
                monthlyRefreshDays = setOf(1, 10, 20),
            )
            env.orderRepository.updateOrderRefresh(orderId, updated)
            assertEquals("1,10,20", env.database.orderDao().findPeriods(orderId).single().monthlyRefreshDays)
            assertEquals("W:2026-01-20", env.orderRepository.observeTodayHosting().first().single().tasks.single().slot.key)

            env.clock.set(Instant.parse("2026-02-05T04:00:00Z"))
            val renewal = env.orderRepository.renewHosting(orderId, "monthly-window-renewal", 30, 10000)
            val renewed = env.database.orderDao().findPeriods(orderId).first { it.id == renewal.periodId }
            assertEquals("1,10,20", renewed.monthlyRefreshDays)
            assertEquals(
                RecurrenceType.WEEKLY,
                env.database.taskDao().findForPeriod(renewed.id).single().recurrence,
            )
        } finally {
            env.close()
        }
    }
}
    
    @Test
    fun monthlyRefreshDaysDriveWeeklyWindowsAndSurviveEditAndRenewal() = runBlocking {
        val env = OrderTestEnvironment(context, initialInstant = Instant.parse("2026-01-20T04:00:00Z"))
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val rules = com.daigan.record.core.time.GameRefreshRules.fromMinutes(
                zoneId = java.time.ZoneId.of("Asia/Shanghai"),
                dailyRefreshMinutes = 240,
                weeklyRefreshDayIso = 1,
                weeklyRefreshMinutes = 300,
                monthlyRefreshDays = setOf(1, 15),
            )
            val orderId = env.orderRepository.createHostingOrder(
                hostingDraft(
                    owner.id, account.id, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31),
                    tasks = listOf(TaskDraft(title = "每周", recurrence = RecurrenceType.WEEKLY)),
                    rules = rules,
                ),
            )
            val period = env.database.orderDao().findPeriods(orderId).single()
            assertEquals("1,15", period.monthlyRefreshDays)
            assertEquals(setOf(1, 15), env.orderRepository.observeOrderDetail(orderId).first()!!.order.monthlyRefreshDays)

            val today = env.orderRepository.observeTodayHosting().first().single()
            // 2026-01-20 is inside the 01-15 -> 02-01 monthly window, not a 7-day one.
            assertEquals("W:2026-01-15", today.tasks.single().slot.key)

            val updated = com.daigan.record.core.time.GameRefreshRules.fromMinutes(
                zoneId = java.time.ZoneId.of("Asia/Shanghai"),
                dailyRefreshMinutes = 240,
                weeklyRefreshDayIso = 1,
                weeklyRefreshMinutes = 300,
                monthlyRefreshDays = setOf(1, 10, 20),
            )
            env.orderRepository.updateOrderRefresh(orderId, updated)
            assertEquals("1,10,20", env.database.orderDao().findPeriods(orderId).single().monthlyRefreshDays)
            assertEquals("W:2026-01-20", env.orderRepository.observeTodayHosting().first().single().tasks.single().slot.key)

            env.clock.set(Instant.parse("2026-02-05T04:00:00Z"))
            val renewal = env.orderRepository.renewHosting(orderId, "monthly-window-renewal", 30, 10000)
            val renewed = env.database.orderDao().findPeriods(orderId).first { it.id == renewal.periodId }
            assertEquals("1,10,20", renewed.monthlyRefreshDays)
            assertEquals(
                RecurrenceType.WEEKLY,
                env.database.taskDao().findForPeriod(renewed.id).single().recurrence,
            )
        } finally {
            env.close()
        }
    }
}

    @Test
    fun monthlyRefreshDaysDriveWeeklyWindowsAndSurviveEditAndRenewal() = runBlocking {
        val env = OrderTestEnvironment(context, initialInstant = Instant.parse("2026-01-20T04:00:00Z"))
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val rules = com.daigan.record.core.time.GameRefreshRules.fromMinutes(
                zoneId = java.time.ZoneId.of("Asia/Shanghai"),
                dailyRefreshMinutes = 240,
                weeklyRefreshDayIso = 1,
                weeklyRefreshMinutes = 300,
                monthlyRefreshDays = setOf(1, 15),
            )
            val orderId = env.orderRepository.createHostingOrder(
                hostingDraft(
                    owner.id, account.id, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31),
                    tasks = listOf(TaskDraft(title = "每周", recurrence = RecurrenceType.WEEKLY)),
                    rules = rules,
                ),
            )
            val period = env.database.orderDao().findPeriods(orderId).single()
            assertEquals("1,15", period.monthlyRefreshDays)
            assertEquals(setOf(1, 15), env.orderRepository.observeOrderDetail(orderId).first()!!.order.monthlyRefreshDays)

            val today = env.orderRepository.observeTodayHosting().first().single()
            // 2026-01-20 is inside the 01-15 -> 02-01 monthly window, not a 7-day one.
            assertEquals("W:2026-01-15", today.tasks.single().slot.key)

            val updated = com.daigan.record.core.time.GameRefreshRules.fromMinutes(
                zoneId = java.time.ZoneId.of("Asia/Shanghai"),
                dailyRefreshMinutes = 240,
                weeklyRefreshDayIso = 1,
                weeklyRefreshMinutes = 300,
                monthlyRefreshDays = setOf(1, 10, 20),
            )
            env.orderRepository.updateOrderRefresh(orderId, updated)
            assertEquals("1,10,20", env.database.orderDao().findPeriods(orderId).single().monthlyRefreshDays)
            assertEquals("W:2026-01-20", env.orderRepository.observeTodayHosting().first().single().tasks.single().slot.key)

            env.clock.set(Instant.parse("2026-02-05T04:00:00Z"))
            val renewal = env.orderRepository.renewHosting(orderId, "monthly-window-renewal", 30, 10000)
            val renewed = env.database.orderDao().findPeriods(orderId).first { it.id == renewal.periodId }
            assertEquals("1,10,20", renewed.monthlyRefreshDays)
            assertEquals(
                RecurrenceType.WEEKLY,
                env.database.taskDao().findForPeriod(renewed.id).single().recurrence,
            )
        } finally {
            env.close()
        }
    }
}
