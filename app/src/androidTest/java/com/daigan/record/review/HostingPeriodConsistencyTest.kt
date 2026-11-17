package com.daigan.record.review

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.core.time.HostingPeriodSelection
import com.daigan.record.core.time.HostingStatus
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.shanghaiRules
import com.daigan.record.testing.writeTestImage
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every hosting surface must agree with every other one, and with the write path.
 *
 * The bug class this file guards: period *selection* used the order row's refresh rules
 * while *scheduling* used each period's stored snapshot. When the two differ, the Today
 * list and the detail page can show a different game day than the one a tap writes to,
 * which either silently records the wrong day or rejects the tap as stale.
 */
@RunWith(AndroidJUnit4::class)
class HostingPeriodConsistencyTest {

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

    /** Simulates a stored snapshot that differs from the order row's current intent. */
    private fun overridePeriodSnapshot(periodId: String, dailyMinutes: Int, weeklyMinutes: Int) {
        env.database.openHelper.writableDatabase.execSQL(
            "UPDATE hosting_periods SET dailyRefreshMinutes = $dailyMinutes, " +
                "weeklyRefreshMinutes = $weeklyMinutes WHERE id = '$periodId'",
        )
    }

    @Test
    fun everySurfaceUsesThePeriodSnapshotAtADailyRefreshBoundary() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
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
        val periodId = env.orderRepository.observeOrderDetail(orderId).first()!!
            .currentPeriod!!.id
        // The period snapshot says the game day flips at 20:00 while the order row still
        // says 04:00. 19:00 Shanghai is therefore game day 10-20 for the order and 10-19
        // for the period that actually owns the work.
        overridePeriodSnapshot(periodId, dailyMinutes = 20 * 60, weeklyMinutes = 20 * 60)

        val at19 = Instant.parse("2026-10-20T11:00:00Z")
        env.clock.set(at19)
        assertEquals(
            "precondition: the order row alone would report a different game day",
            LocalDate.of(2026, 10, 20),
            GameCalendar.gameDate(at19, shanghaiRules),
        )

        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val todayPage = env.orderRepository.observeTodayHosting().first().single()
        val summary = env.orderRepository.observeOrdersForOwner(owner.id).first().single()
        val reminder = com.daigan.record.notifications.ReminderSnapshotBuilder.build(
            orders = listOf(env.database.orderDao().findById(orderId)!!),
            accounts = env.database.gameAccountDao().findAll(),
            owners = env.database.ownerDao().findAll(),
            periods = env.database.orderDao().findPeriods(orderId),
            tasks = env.database.taskDao().findForOrder(orderId),
            occurrences = env.database.occurrenceDao().findForOrder(orderId),
            now = at19,
        ).orders.single()

        val periodGameDate = LocalDate.of(2026, 10, 19)
        assertEquals(periodGameDate, detail.today)
        assertEquals(periodGameDate, todayPage.today)
        assertEquals(periodId, todayPage.period.id)
        assertEquals(periodId, summary.currentPeriod!!.id)
        assertEquals(HostingStatus.ACTIVE, todayPage.status)
        // Reminders resolve the same period and therefore the same expiry day.
        assertEquals(LocalDate.of(2026, 10, 31), reminder.period!!.endDate)
        assertEquals(periodGameDate, detail.today)
        val expectedKey = "D:$periodGameDate"
        assertEquals(expectedKey, detail.currentHostingTasks().single().slot.key)
        assertEquals(expectedKey, todayPage.tasks.single().slot.key)

        // The write path resolves the same pair, so the displayed key is accepted.
        env.orderRepository.setOccurrenceCompleted(
            todayPage.tasks.single().task.id,
            true,
            expectedKey,
        )
        val occurrence = env.orderRepository.observeOrderDetail(orderId).first()!!
            .occurrences.single()
        assertEquals(expectedKey, occurrence.occurrenceKey)
        assertEquals(periodGameDate, occurrence.attributedDate)

        // Crossing the period's own 20:00 boundary moves every surface together.
        val at20 = Instant.parse("2026-10-20T12:10:00Z")
        env.clock.set(at20)
        assertEquals(LocalDate.of(2026, 10, 20), env.orderRepository
            .observeOrderDetail(orderId).first()!!.today)
        assertEquals("D:2026-10-20", env.orderRepository
            .observeTodayHosting().first().single().tasks.single().slot.key)
        val stale = runCatching {
            env.orderRepository.setOccurrenceCompleted(
                todayPage.tasks.single().task.id,
                false,
                expectedKey,
            )
        }.exceptionOrNull()
        assertTrue(
            "the previous game day's key must be rejected after the boundary",
            stale is com.daigan.record.data.repository.StaleOccurrenceException,
        )
        assertEquals(
            "a rejected stale write must not touch the recorded occurrence",
            1,
            env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences.size,
        )
    }

    @Test
    fun refreshRuleEditUpdatesCurrentAndFutureSnapshotsAndKeepsRecordedWork() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
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
        val taskId = before.currentHostingTasks().single().task.id
        env.orderRepository.setOccurrenceCompleted(taskId, true)
        val occurrenceBefore = env.orderRepository.observeOrderDetail(orderId).first()!!
            .occurrences.single()
        env.attachmentRepository.addAttachment(
            taskDefinitionId = null,
            occurrenceId = occurrenceBefore.id,
            sourceUri = Uri.fromFile(writeTestImage(context)),
        )
        val future = env.orderRepository.renewHosting(orderId, "future-op", 7, 10000)
        env.receiptRepository.recordPayment(
            com.daigan.record.data.model.PaymentRequest(
                orderId = orderId,
                periodId = before.currentPeriod!!.id,
                // The draft is settled in one payment, so it must cover the period price.
                amountCents = 30000,
                receivedAt = env.clock.now(),
                channel = com.daigan.record.data.model.PaymentChannel.WECHAT,
                note = "定金",
                operationId = "receipt-before-refresh-edit",
            ),
        )
        val receiptsBefore = env.orderRepository.observeOrderDetail(orderId).first()!!.receipts

        // Only the daily refresh time changes: no occurrence identity depends on it.
        val newRules = GameRefreshRules(
            zoneId = ZoneId.of("Asia/Shanghai"),
            dailyRefresh = LocalTime.of(6, 0),
            weeklyRefreshDay = DayOfWeek.MONDAY,
            weeklyRefreshTime = LocalTime.of(5, 0),
        )
        env.orderRepository.updateOrderRefresh(orderId, newRules)

        val periods = env.database.orderDao().findPeriods(orderId)
        assertEquals("both the running and the scheduled period follow the new rule",
            2, periods.count { it.dailyRefreshMinutes == 360 })
        assertEquals(2, periods.count { it.weeklyRefreshDay == 1 && it.weeklyRefreshMinutes == 300 })
        val order = env.database.orderDao().findById(orderId)!!
        assertEquals(360, order.dailyRefreshMinutes)

        val after = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(occurrenceBefore.id, after.occurrences.single().id)
        assertEquals(occurrenceBefore.occurrenceKey, after.occurrences.single().occurrenceKey)
        assertEquals(occurrenceBefore.attributedDate, after.occurrences.single().attributedDate)
        assertEquals(occurrenceBefore.completedAt, after.occurrences.single().completedAt)
        assertEquals(1, after.attachments.size)
        assertEquals(receiptsBefore.map { it.id }, after.receipts.map { it.id })
        assertEquals(receiptsBefore.map { it.amountCents }, after.receipts.map { it.amountCents })
        assertEquals(future.periodId, after.futurePeriods.single().id)

        // A change that would re-identify recorded work is refused and writes nothing.
        val conflicting = GameRefreshRules(
            zoneId = ZoneId.of("Asia/Shanghai"),
            dailyRefresh = LocalTime.of(4, 0),
            weeklyRefreshDay = DayOfWeek.TUESDAY,
            weeklyRefreshTime = LocalTime.of(9, 0),
        )
        val failure = runCatching { env.orderRepository.updateOrderRefresh(orderId, conflicting) }
            .exceptionOrNull()
        assertNotNull("weekly identity changes must be refused next to recorded work", failure)
        assertTrue(failure!!.message!!.contains("已有打卡记录"))

        val unchangedOrder = env.database.orderDao().findById(orderId)!!
        assertEquals("the refused edit must not write the order row either", 360, unchangedOrder.dailyRefreshMinutes)
        assertEquals(1, unchangedOrder.weeklyRefreshDay)
        val unchangedPeriods = env.database.orderDao().findPeriods(orderId)
        assertEquals(listOf(360, 360), unchangedPeriods.map { it.dailyRefreshMinutes })
        assertEquals(listOf(300, 300), unchangedPeriods.map { it.weeklyRefreshMinutes })
        val afterFailure = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(occurrenceBefore.id, afterFailure.occurrences.single().id)
        assertEquals(occurrenceBefore.attributedDate, afterFailure.occurrences.single().attributedDate)
        assertEquals(occurrenceBefore.occurrenceKey, afterFailure.occurrences.single().occurrenceKey)
        assertEquals(1, afterFailure.attachments.size)
    }

    @Test
    fun twoWeeklyOccurrencesCanShareThePeriodStartDateWithoutColliding() = runBlocking {
        // Default rules (daily 04:00, weekly Monday 05:00) and a period starting on the
        // weekly refresh weekday: the previous window still overlaps the first game day,
        // so both occurrences are clamped to the period's first date.
        env.clock.set(Instant.parse("2026-10-12T02:00:00Z"))
        val (owner, account) = env.seedOwnerAndAccount()
        val start = LocalDate.of(2026, 10, 12)
        assertTrue("fixture must start on the weekly refresh weekday", start.dayOfWeek == DayOfWeek.MONDAY)
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                owner.id,
                account.id,
                start,
                LocalDate.of(2026, 11, 11),
                tasks = listOf(TaskDraft(title = "周常", recurrence = com.daigan.record.data.model.RecurrenceType.WEEKLY)),
            ),
        )
        val period = env.orderRepository.observeOrderDetail(orderId).first()!!.currentPeriod!!
        val taskId = env.orderRepository.observeOrderDetail(orderId).first()!!
            .tasksForPeriod(period.id).single().id

        env.orderRepository.setOccurrenceCompleted(taskId, true)
        env.orderRepository.setHistoricalCompletion(
            orderId = orderId,
            periodId = period.id,
            taskId = taskId,
            date = start,
            completed = true,
            weeklyWindowStart = start.minusDays(7),
        )

        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(2, detail.occurrences.size)
        assertEquals(
            "both windows really do clamp to the period's first date",
            setOf(start),
            detail.occurrences.map { it.attributedDate }.toSet(),
        )
        val entries = detail.historyEntries()
        assertEquals(2, entries.size)
        assertEquals(
            "taskId + date is not a unique identity for these rows",
            1,
            entries.map { "${it.taskId}-${it.attributedDate}" }.toSet().size,
        )
        assertEquals(
            "the occurrence row is the real identity",
            2,
            entries.map { it.stableKey }.toSet().size,
        )
        assertEquals(2, entries.map { it.occurrenceId }.filterNotNull().toSet().size)
    }

    @Test
    fun todayListIgnoresPeriodsThatHaveNotStartedAndKeepsExpiredOnesVisible() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
        )
        val renewal = env.orderRepository.renewHosting(orderId, "next-month", 30, 10000)
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals("the future period is never the current one", 0, detail.currentPeriod!!.periodIndex)
        assertEquals(renewal.periodId, detail.futurePeriods.single().id)

        // Before the order starts, the display fallback is the first scheduled period and
        // it is explicitly NOT_STARTED — not "the period being served".
        val notStartedOrder = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 20)),
        )
        val notStarted = env.orderRepository.observeOrderDetail(notStartedOrder).first()!!
        assertEquals(HostingStatus.NOT_STARTED, notStarted.currentStatus())
        assertTrue(notStarted.currentHostingTasks().isEmpty())

        // 10-31 is the inclusive last day, so the period is still ACTIVE then.
        env.clock.set(Instant.parse("2026-10-31T02:00:00Z"))
        assertEquals(
            HostingStatus.ACTIVE,
            env.orderRepository.observeOrderDetail(orderId).first()!!.currentStatus(),
        )

        // After every period ends, the last ended period stays visible with EXPIRED status.
        env.clock.set(Instant.parse("2026-12-05T02:00:00Z"))
        val expired = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(HostingStatus.EXPIRED, expired.currentStatus())
        assertEquals("the last ended period is the one shown", 1, expired.currentPeriod!!.periodIndex)
        assertTrue(expired.futurePeriods.isEmpty())
        val expiredToday = env.orderRepository.observeTodayHosting().first()
            .first { it.order.id == orderId }
        assertEquals(HostingStatus.EXPIRED, expiredToday.status)
        assertTrue(expiredToday.expired)
        assertTrue(expiredToday.tasks.isEmpty())

        // Renewal after expiry starts on the current game date, not after a past period.
        val late = env.orderRepository.renewHosting(orderId, "after-expiry", 5, 10000)
        assertEquals(LocalDate.of(2026, 12, 5), late.startDate)
        assertEquals(LocalDate.of(2026, 12, 9), late.endDate)
        assertEquals(2, late.periodIndex)
    }

    @Test
    fun renewalStartDateUsesTheLastScheduledPeriodsOwnSnapshot() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 19)),
        )
        val period = env.orderRepository.observeOrderDetail(orderId).first()!!.currentPeriod!!
        // 2026-10-21 00:30 Shanghai. The order row (04:00 refresh) still says game day
        // 10-20; the period's own snapshot (00:00 refresh) already says 10-21. The
        // renewal decision belongs to the period, so it must resume on 10-21, not 10-20.
        overridePeriodSnapshot(period.id, dailyMinutes = 0, weeklyMinutes = 300)
        val now = Instant.parse("2026-10-20T16:30:00Z")
        env.clock.set(now)
        assertEquals(
            "precondition: the two rule sets disagree about today",
            LocalDate.of(2026, 10, 20),
            GameCalendar.gameDate(now, shanghaiRules),
        )
        val result = env.orderRepository.renewHosting(orderId, "own-snapshot", 3, 10000)
        assertEquals(LocalDate.of(2026, 10, 21), result.startDate)
        assertEquals(LocalDate.of(2026, 10, 23), result.endDate)
        assertEquals(
            HostingPeriodSelection.nextStartDate(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21)),
            result.startDate,
        )
    }
    @Test
    fun repeatedRefreshEditsNeverReactivateAnEndedSnapshot() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-21T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20)),
        )
        val changed = shanghaiRules.copy(dailyRefresh = LocalTime.of(23, 0))
        env.orderRepository.updateOrderRefresh(orderId, changed)
        env.orderRepository.updateOrderRefresh(orderId, changed)
        val after = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(HostingStatus.EXPIRED, after.currentStatus())
        assertEquals(240, env.database.orderDao().findPeriods(orderId).single().dailyRefreshMinutes)
        assertTrue(after.currentHostingTasks().isEmpty())
    }

    @Test
    fun refreshProtectionComparesTheActualPeriodSnapshot() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(owner.id, account.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
        )
        val periodId = env.database.orderDao().findPeriods(orderId).single().id
        env.database.openHelper.writableDatabase.execSQL(
            "UPDATE hosting_periods SET weeklyRefreshDay = 2 WHERE id = ?", arrayOf(periodId),
        )
        val task = env.orderRepository.observeTodayHosting().first().single().tasks.single()
        env.orderRepository.setOccurrenceCompleted(task.task.id, true, task.slot.key)
        val before = env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences
        val failure = runCatching { env.orderRepository.updateOrderRefresh(orderId, shanghaiRules) }.exceptionOrNull()
        assertNotNull("a real period rule change next to recorded work must be rejected", failure)
        assertEquals(2, env.database.orderDao().findPeriods(orderId).single().weeklyRefreshDay)
        assertEquals(before, env.orderRepository.observeOrderDetail(orderId).first()!!.occurrences)
    }

}
