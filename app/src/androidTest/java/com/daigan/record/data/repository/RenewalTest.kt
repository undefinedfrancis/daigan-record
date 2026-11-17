package com.daigan.record.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.core.time.HostingPeriodSelection
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.writeTestImage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenewalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() {
        File(context.filesDir, "attachments").deleteRecursively()
        env = OrderTestEnvironment(context)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun renewalKeepsTheOrderAndPreservesEveryOldPeriodRecord() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-05T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 10),
                priceYuan = "300.00",
                tasks = listOf(TaskDraft(title = "签到"), TaskDraft(title = "副本")),
            ),
        )
        val before = env.orderRepository.observeOrderDetail(orderId).first()!!
        val oldPeriod = before.currentPeriod!!
        val oldTasks = before.tasksForPeriod(oldPeriod.id)
        env.orderRepository.setOccurrenceCompleted(oldTasks.first().id, true)
        val oldOccurrence = env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.single()
        env.attachmentRepository.addAttachment(
            taskDefinitionId = null,
            occurrenceId = oldOccurrence.id,
            sourceUri = Uri.fromFile(writeTestImage(context)),
        )

        env.clock.set(Instant.parse("2026-10-17T02:00:00Z"))
        val result = env.orderRepository.renewHosting(
            orderId = orderId,
            operationId = "renew-operation-1",
            durationDays = 30,
            priceCents = 50000,
        )
        assertTrue(!result.alreadyApplied)
        assertEquals(1, result.periodIndex)
        assertEquals(LocalDate.of(2026, 10, 17), result.startDate)
        assertEquals(LocalDate.of(2026, 11, 15), result.endDate)

        val after = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals("Renewal must not create a new order", orderId, after.order.id)
        assertEquals(1, env.orderRepository.observeOrdersForOwner(owner.id).first().size)
        assertEquals(2, after.periods.size)
        assertEquals(50000L, after.currentPeriod!!.priceCents)
        assertEquals(30000L, after.periods.first { it.id == oldPeriod.id }.priceCents)
        assertEquals(LocalDate.of(2026, 10, 1), after.periods.first { it.id == oldPeriod.id }.startDate)
        assertEquals(LocalDate.of(2026, 10, 10), after.periods.first { it.id == oldPeriod.id }.endDate)

        val newTasks = after.tasksForPeriod(after.currentPeriod!!.id)
        assertEquals(2, newTasks.size)
        assertTrue("New period task snapshots are independent", newTasks.map { it.id }.none { id -> oldTasks.any { it.id == id } })
        assertTrue(newTasks.all { it.completedAt == null })
        assertEquals(oldTasks.map { it.title }.toSet(), newTasks.map { it.title }.toSet())
        assertEquals(oldTasks.map { it.id }.toSet(), after.tasksForPeriod(oldPeriod.id).map { it.id }.toSet())
        assertEquals(
            "Old completion and screenshot survive",
            oldOccurrence.id,
            after.occurrences.single { it.taskDefinitionId == oldTasks.first().id }.id,
        )
        assertEquals(1, after.attachments.size)
    }

    @Test
    fun duplicateRenewalSubmissionUsesThePersistedOperationId() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-17T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10)),
        )
        val first = env.orderRepository.renewHosting(orderId, "duplicate-op", 30, 10000)
        val second = env.orderRepository.renewHosting(orderId, "duplicate-op", 30, 10000)
        assertTrue(second.alreadyApplied)
        assertEquals(first.periodId, second.periodId)
        assertEquals(first.startDate, second.startDate)
        assertEquals(first.endDate, second.endDate)
        assertEquals(2, env.orderRepository.observeOrderDetail(orderId).first()!!.periods.size)

        // A *different* operation is a genuinely new renewal, not a retry: it appends
        // another period after the last scheduled one and never overlaps it. The old
        // expectation ("a second operation id is rejected") contradicted UF-020, where
        // several future periods may already be scheduled.
        val third = env.orderRepository.renewHosting(orderId, "another-op", 10, 20000)
        assertFalse(third.alreadyApplied)
        assertEquals(2, third.periodIndex)
        // First renewal: 10-17 .. 11-15 (30 inclusive days). Second: 11-16 .. 11-25.
        assertEquals(LocalDate.of(2026, 11, 16), third.startDate)
        assertEquals(LocalDate.of(2026, 11, 25), third.endDate)
        val periods = env.orderRepository.observeOrderDetail(orderId).first()!!.periods
            .sortedBy { it.periodIndex }
        assertEquals(listOf(0, 1, 2), periods.map { it.periodIndex })
        for (index in 1 until periods.size) {
            assertTrue(
                "period ${index + 1} must start after period $index ends",
                periods[index].startDate.isAfter(periods[index - 1].endDate),
            )
        }
        assertEquals("Each renewal keeps its own price", 20000L, periods[2].priceCents)
    }

    @Test
    fun earlyRenewalKeepsTheRunningPeriodActionable() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        // 2026-10-20 10:00 in Shanghai: the 10-01..10-31 period is running.
        env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31),
                tasks = listOf(TaskDraft(title = "签到")),
            ),
        )
        val before = env.orderRepository.observeOrderDetail(orderId).first()!!
        val runningPeriod = before.currentPeriod!!
        assertEquals(0, runningPeriod.periodIndex)
        val taskId = before.tasksForPeriod(runningPeriod.id).single().id

        // Finish today's work and remember the exact occurrence identity.
        env.orderRepository.setOccurrenceCompleted(taskId, true)
        val completed = env.orderRepository.observeOrderDetail(orderId).first()!!
            .occurrences.single()
        assertEquals("D:2026-10-20", completed.occurrenceKey)
        assertEquals(LocalDate.of(2026, 10, 20), completed.attributedDate)

        val renewal = env.orderRepository.renewHosting(orderId, "early-op", 30, 50000)
        assertFalse(renewal.alreadyApplied)
        assertEquals(1, renewal.periodIndex)
        assertEquals("Early renewal continues after the running period", LocalDate.of(2026, 11, 1), renewal.startDate)
        assertEquals(LocalDate.of(2026, 11, 30), renewal.endDate)

        val after = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(2, after.periods.size)
        // The running period keeps owning "today"; the new one is only scheduled.
        assertEquals(runningPeriod.id, after.currentPeriod!!.id)
        assertEquals(LocalDate.of(2026, 10, 20), after.today)
        assertEquals(runningPeriod.id, after.effectivePeriod!!.id)
        assertEquals(listOf(renewal.periodId), after.futurePeriods.map { it.id })
        assertTrue(after.historyEntries().any { it.occurrenceId == completed.id })
        assertEquals(LocalDate.of(2026, 10, 20), after.today)
        // Today's task list still belongs to the old period and the completion survives.
        val todayTasks = after.currentHostingTasks()
        assertEquals(1, todayTasks.size)
        assertTrue(todayTasks.single().completed)
        assertEquals("D:2026-10-20", todayTasks.single().slot.key)

        val todayPage = env.orderRepository.observeTodayHosting().first().single()
        assertEquals(runningPeriod.id, todayPage.period.id)
        assertEquals(LocalDate.of(2026, 10, 20), todayPage.today)
        assertEquals(1, todayPage.tasks.size)
        assertTrue(todayPage.tasks.single().completed)

        // The summary card counts the running period, not the empty future one.
        val summary = env.orderRepository.observeOrdersForOwner(owner.id).first().single()
        assertEquals(runningPeriod.id, summary.currentPeriod!!.id)
        assertEquals(1, summary.taskCount)
        assertEquals(1, summary.completedTaskCount)

        // A task of the future period must not be tappable today.
        val futureTask = after.tasksForPeriod(renewal.periodId).single()
        val stale = runCatching {
            env.orderRepository.setOccurrenceCompleted(futureTask.id, true)
        }.exceptionOrNull()
        assertNotNull("A future period has no occurrence today", stale)
        assertTrue(after.occurrences.none { it.taskDefinitionId == futureTask.id })
    }

    @Test
    fun renewalBeforeDailyRefreshStartsOnTheCurrentGameDate() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 16),
                tasks = listOf(TaskDraft(title = "签到")),
            ),
        )

        // 03:30 on 10-18 in Shanghai is still game day 10-17.
        env.clock.set(Instant.parse("2026-10-17T19:30:00Z"))
        val result = env.orderRepository.renewHosting(orderId, "before-refresh", 10, 10000)
        assertEquals(LocalDate.of(2026, 10, 17), result.startDate)
        assertEquals(LocalDate.of(2026, 10, 26), result.endDate)

        // No work is materialised before the new period starts; today's game day is
        // already inside the new period.
        assertEquals(0, env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.size)
        val today = env.orderRepository.observeTodayHosting().first().single()
        assertEquals(LocalDate.of(2026, 10, 17), today.today)
        assertEquals(1, today.tasks.size)
        env.orderRepository.setOccurrenceCompleted(today.tasks.single().task.id, true)
        assertEquals(
            "D:2026-10-17",
            env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.single().occurrenceKey,
        )
    }

    @Test
    fun renewalAfterDailyRefreshStartsOnTheNextGameDate() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 16)),
        )
        // 04:30 on 10-18 in Shanghai is game day 10-18.
        env.clock.set(Instant.parse("2026-10-17T20:30:00Z"))
        val result = env.orderRepository.renewHosting(orderId, "after-refresh", 10, 10000)
        assertEquals(LocalDate.of(2026, 10, 18), result.startDate)
        assertEquals(LocalDate.of(2026, 10, 27), result.endDate)
        assertEquals(LocalDate.of(2026, 10, 18), env.orderRepository.observeTodayHosting().first().single().today)
    }

    @Test
    fun renewalPreviewPersistedDatesAndResultAgreeAcrossRefreshBoundaries() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
        )
        // Two renewals from the same running order, the second one after the state changed.
        val first = env.orderRepository.renewHosting(orderId, "preview-1", 30, 10000)
        val afterFirst = env.orderRepository.observeOrderDetail(orderId).first()!!
        // What the dialog computes: continue after the *last scheduled* period.
        val previewStart = HostingPeriodSelection.nextStartDate(
            afterFirst.latestPeriod!!.endDate,
            afterFirst.today,
        )
        val previewEnd = previewStart.plusDays(9)

        // 03:50 Shanghai on 12-01 is still game day 11-30; the renewal must not depend on
        // that boundary because the last scheduled period ends 11-30.
        env.clock.set(Instant.parse("2026-11-30T19:50:00Z"))
        val second = env.orderRepository.renewHosting(orderId, "preview-2", 10, 20000)
        assertEquals(previewStart, second.startDate)
        assertEquals(previewEnd, second.endDate)

        // 04:10 Shanghai; the previewed dates are unchanged, only "today" moved.
        env.clock.set(Instant.parse("2026-11-30T20:10:00Z"))
        val persisted = env.orderRepository.observeOrderDetail(orderId).first()!!
            .periods.single { it.id == second.periodId }
        assertEquals(second.startDate, persisted.startDate)
        assertEquals(second.endDate, persisted.endDate)
        assertEquals(10, persisted.durationDays)
        assertEquals(1, first.periodIndex)
        assertEquals(2, second.periodIndex)
        assertEquals(
            "Inclusive end: a 10 day period starting 12-01 ends 12-10",
            LocalDate.of(2026, 12, 10),
            second.endDate,
        )
    }

    @Test
    fun manyFuturePeriodsStayContiguousAndNeverOverlap() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-05T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
        )
        val durations = listOf(7, 14, 3, 30)
        val results = durations.mapIndexed { index, days ->
            env.orderRepository.renewHosting(orderId, "multi-$index", days, 1000L * (index + 1))
        }
        assertEquals(listOf(1, 2, 3, 4), results.map { it.periodIndex })
        assertEquals(LocalDate.of(2026, 11, 1), results.first().startDate)
        for (index in 1 until results.size) {
            assertEquals(
                "period ${index + 1} starts the day after period $index ends",
                results[index - 1].endDate.plusDays(1),
                results[index].startDate,
            )
            assertEquals(
                "inclusive duration",
                durations[index].toLong(),
                results[index].endDate.toEpochDay() - results[index].startDate.toEpochDay() + 1,
            )
        }
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(5, detail.periods.size)
        // Four future periods, all labelled future, none reported as the current one.
        assertEquals(4, detail.futurePeriods.size)
        assertEquals(0, detail.currentPeriod!!.periodIndex)
        assertEquals("Future periods keep their own prices", 4000L, detail.latestPeriod!!.priceCents)
        assertEquals(
            "Task snapshots are copied for every future period",
            4,
            detail.futurePeriods.sumOf { detail.tasksForPeriod(it.id).size },
        )
        // Today's page still shows only the running period.
        val today = env.orderRepository.observeTodayHosting().first().single()
        assertEquals(0, today.period.periodIndex)
    }

    @Test
    fun renewalPreservesPreviousPeriodTasksEvenWhenNewPeriodChangesNothing() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-17T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 10),
                tasks = listOf(TaskDraft(title = "只做一次")),
            ),
        )
        val oldTaskId = env.orderRepository.observeOrderDetail(orderId).first()!!.tasks.single().id
        env.orderRepository.renewHosting(orderId, "preserve-op", 5, 10000)
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val oldTask = detail.tasks.first { it.id == oldTaskId }
        val newTask = detail.tasks.first { it.id != oldTaskId }
        assertNotEquals(oldTask.id, newTask.id)
        assertEquals(oldTask.title, newTask.title)
        assertEquals(oldTask.recurrence, newTask.recurrence)
        assertEquals(10000L, detail.currentPeriod!!.priceCents)
        assertEquals(30000L, detail.periods.first { it.periodIndex == 0 }.priceCents)
    }
}
