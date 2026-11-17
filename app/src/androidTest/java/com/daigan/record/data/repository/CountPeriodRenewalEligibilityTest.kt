package com.daigan.record.data.repository

import androidx.test.core.app.ApplicationProvider
import com.daigan.record.data.local.toModel
import com.daigan.record.data.model.CountHosting
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.shanghaiRules
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage-3 repair F1: the count period's stored `startDate`/`endDate` pair is a **storage
 * anchor** (it only exists so `(orderId, startDate)` stays unique), never the day the
 * renewed agreement starts serving.
 *
 * A renewal created on the same game day pushes that anchor forward by one day, so treating
 * it as the service lower bound rejects work that legitimately happened today. These tests
 * drive the real repositories and Room: renew, ensure the new round, and submit a valid
 * cumulative total on the game day the agreement began.
 */
class CountPeriodRenewalEligibilityTest {

    private lateinit var env: OrderTestEnvironment
    private lateinit var progress: CumulativeProgressRepository

    private val day1 = LocalDate.parse("2026-10-17")
    private val day2 = LocalDate.parse("2026-10-18")
    private val day3 = LocalDate.parse("2026-10-19")

    @Before
    fun setUp() {
        env = OrderTestEnvironment(ApplicationProvider.getApplicationContext())
        progress = CumulativeProgressRepository(
            db = env.database,
            orderDao = env.database.orderDao(),
            taskDao = env.database.taskDao(),
            occurrenceDao = env.database.occurrenceDao(),
            eventDao = env.database.taskProgressEventDao(),
            hostingSessionDao = env.database.hostingSessionDao(),
            accountDao = env.database.gameAccountDao(),
            ownerDao = env.database.ownerDao(),
            timeProvider = env.clock,
            countSessions = env.countSessionService,
        )
    }

    @After
    fun tearDown() {
        env.database.close()
    }

    // ---------------------------------------------------------------- fixtures

    private fun cumulativeTask(target: String = "100") = TaskDraft(
        title = "累计抽数",
        progressType = ProgressType.NUMERIC,
        numericMode = NumericMode.CUMULATIVE,
        startValue = "0",
        targetValue = target,
        recurrence = RecurrenceType.DAILY,
    )

    private suspend fun createCountOrder(target: Int): String {
        val (owner, account) = env.seedOwnerAndAccount()
        return env.orderRepository.createHostingOrder(
            HostingOrderDraft(
                ownerId = owner.id,
                accountId = account.id,
                title = "打卡托管",
                startDate = day1,
                endDate = day1,
                priceYuan = "100.00",
                paymentMode = PaymentMode.ONE_TIME,
                refresh = shanghaiRules,
                tasks = listOf(cumulativeTask()),
                hostingMode = HostingMode.COUNT_BASED,
                targetSessions = target,
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
    }

    private suspend fun periods(orderId: String) =
        env.database.orderDao().findPeriods(orderId).sortedBy { it.periodIndex }

    private suspend fun taskOf(periodId: String) =
        env.database.taskDao().findForPeriod(periodId).single()

    private suspend fun round(orderId: String, index: Int) = checkNotNull(
        env.database.hostingSessionDao().findByIndex(orderId, index),
    )

    private fun advanceTo(date: LocalDate) {
        env.clock.set(Instant.parse("${date}T02:00:00Z"))
    }

    private suspend fun submit(
        taskId: String,
        sessionId: String,
        total: String,
        date: LocalDate,
    ): ProgressCommandResult = progress.submitTotal(
        SubmitTotalCommand(
            operationId = UUID.randomUUID().toString(),
            taskId = taskId,
            scope = ProgressScope.Session(sessionId),
            attributedGameDate = date,
            total = BigDecimal(total),
        ),
    )

    private suspend fun completedRounds(orderId: String, periodId: String): Int =
        CountHosting.periodCompletedCount(
            env.database.hostingSessionDao().findForOrder(orderId).map { it.toModel() },
            periodId,
        )

    /** Finish period 1's round on [day1] so the order has a real, complete history. */
    private suspend fun finishFirstPeriod(orderId: String) {
        val period1 = periods(orderId).single()
        val task1 = taskOf(period1.id)
        val round1 = round(orderId, 1)
        submit(task1.id, round1.id, "100", day1)
        assertNotNull("round 1 is complete before any renewal", round(orderId, 1).completedAt)
    }

    // ---------------------------------------------------------------- F1

    /**
     * The failing case: renew on the **same game day** the previous period was finished,
     * keep the cumulative task in the new period, and submit a valid total today.
     */
    @Test
    fun sameDayRenewedPeriodAcceptsCumulativeWorkForThatGameDay() = runBlocking {
        val orderId = createCountOrder(target = 1)
        finishFirstPeriod(orderId)
        assertNotNull("the agreed count is reached", env.database.orderDao().findById(orderId)?.completedAt)

        val renewal = env.orderRepository.renewCountHosting(
            orderId = orderId,
            operationId = "renew-same-day-${UUID.randomUUID()}",
            targetSessions = 2,
            priceCents = 5000,
        )
        val period2 = checkNotNull(env.database.orderDao().findPeriod(renewal.periodId))
        assertEquals("the anchor is pushed forward, not a service date", day2, period2.startDate)
        assertNull("renewal reopens the order", env.database.orderDao().findById(orderId)?.completedAt)

        val task2 = taskOf(period2.id)
        val round2 = progress.ensureCountSession(orderId).session
        assertEquals(2, round2.sessionIndex)
        assertEquals(period2.id, round2.periodId)

        val applied = submit(task2.id, round2.id, "40", day1)

        assertTrue("today's cumulative work belongs to the renewed period", applied is ProgressCommandResult.Applied)
        assertEquals(ProgressScope.Session(round2.id), applied.event.scope)
        assertEquals("attribution date is the real service day", day1, applied.event.gameDate)

        // Owning period + fresh scope: nothing is inherited from round 1.
        val status = checkNotNull(progress.cumulativeStatus(task2.id, ProgressScope.Session(round2.id)))
        assertEquals(0, status.total.compareTo(BigDecimal("40")))
        assertEquals(0, completedRounds(orderId, period2.id))

        // Old history is untouched.
        val period1 = periods(orderId).first()
        val task1 = taskOf(period1.id)
        assertEquals(1, progress.progressHistory(task1.id).size)
        assertEquals(0, progress.progressHistory(task1.id).single().totalValue!!.compareTo(BigDecimal("100")))
        assertNotNull("round 1 keeps its completion", round(orderId, 1).completedAt)

        // A day before the agreement existed is still rejected: the lower bound did not move.
        val tooEarly = runCatching { submit(task2.id, round2.id, "45", day1.minusDays(1)) }
            .exceptionOrNull()
        assertEquals("OUT_OF_DATE_WINDOW", (tooEarly as ProgressCommandException).code)
        assertEquals("no write happened", 1, progress.progressHistory(task2.id).size)
    }

    /** Two renewals on one game day must both serve that game day. */
    @Test
    fun twoSameDayRenewalsBothServeThatGameDay() = runBlocking {
        val orderId = createCountOrder(target = 1)
        finishFirstPeriod(orderId)

        val first = env.orderRepository.renewCountHosting(
            orderId = orderId,
            operationId = "renew-a-${UUID.randomUUID()}",
            targetSessions = 1,
            priceCents = 1000,
        )
        val second = env.orderRepository.renewCountHosting(
            orderId = orderId,
            operationId = "renew-b-${UUID.randomUUID()}",
            targetSessions = 1,
            priceCents = 2000,
        )
        val period2 = checkNotNull(env.database.orderDao().findPeriod(first.periodId))
        val period3 = checkNotNull(env.database.orderDao().findPeriod(second.periodId))
        assertEquals(day2, period2.startDate)
        assertEquals(day3, period3.startDate)

        // Period 2: a whole round on the renewal day itself.
        val round2 = progress.ensureCountSession(orderId).session
        assertEquals(period2.id, round2.periodId)
        submit(taskOf(period2.id).id, round2.id, "100", day1)
        assertNotNull("period 2 rounds count on their real service day", round(orderId, round2.sessionIndex).completedAt)
        assertEquals(1, completedRounds(orderId, period2.id))

        // Period 3 starts next in periodIndex order and serves the same game day.
        val round3 = progress.ensureCountSession(orderId).session
        assertEquals(period3.id, round3.periodId)
        submit(taskOf(period3.id).id, round3.id, "100", day1)
        assertEquals(1, completedRounds(orderId, period3.id))
        assertNotNull(
            "every period target met on one game day",
            env.database.orderDao().findById(orderId)?.completedAt,
        )

        val events = progress.progressHistory(taskOf(period3.id).id)
        assertEquals(day1, events.single().gameDate)
        assertEquals(ProgressScope.Session(round3.id), events.single().scope)
    }

    /**
     * Refresh/time-zone boundary: the period was created at 03:30, i.e. on game day 10-17,
     * while its anchor is 10-18. The agreement therefore serves 10-17 and 10-18, and never
     * 10-16.
     */
    @Test
    fun renewalAroundTheDailyRefreshKeepsTheGameDayItWasCreatedOn() = runBlocking {
        val orderId = createCountOrder(target = 1)
        finishFirstPeriod(orderId)

        env.clock.set(Instant.parse("2026-10-17T19:30:00Z")) // 03:30 on 10-18 = game day 10-17
        val renewal = env.orderRepository.renewCountHosting(
            orderId = orderId,
            operationId = "renew-before-refresh-${UUID.randomUUID()}",
            targetSessions = 2,
            priceCents = 5000,
        )
        val period2 = checkNotNull(env.database.orderDao().findPeriod(renewal.periodId))
        assertEquals("anchor is the day after the old one", day2, period2.startDate)

        val task2 = taskOf(period2.id)
        val round2 = progress.ensureCountSession(orderId).session
        assertEquals(period2.id, round2.periodId)

        // Still game day 10-17: the renewed agreement already serves it.
        val applied = submit(task2.id, round2.id, "30", day1)
        assertTrue(applied is ProgressCommandResult.Applied)

        // After the refresh the same round keeps accepting the next game day.
        advanceTo(day2)
        val nextDay = submit(task2.id, round2.id, "60", day2)
        assertTrue(nextDay is ProgressCommandResult.Applied)
        assertEquals(2, progress.progressHistory(task2.id).size)

        // The day before the agreement was created is still out of range.
        val tooEarly = runCatching { submit(task2.id, round2.id, "70", day1.minusDays(1)) }
            .exceptionOrNull()
        assertEquals("OUT_OF_DATE_WINDOW", (tooEarly as ProgressCommandException).code)
        assertEquals(2, progress.progressHistory(task2.id).size)
    }

    /**
     * A renewal appended later keeps the real lower bound: work attributed before the
     * agreement was created stays rejected even though the anchor rule changed.
     */
    @Test
    fun laterRenewalRejectsGameDaysBeforeItWasCreated() = runBlocking {
        val orderId = createCountOrder(target = 1)
        finishFirstPeriod(orderId)

        advanceTo(day2)
        val renewal = env.orderRepository.renewCountHosting(
            orderId = orderId,
            operationId = "renew-later-${UUID.randomUUID()}",
            targetSessions = 2,
            priceCents = 5000,
        )
        val period2 = checkNotNull(env.database.orderDao().findPeriod(renewal.periodId))
        assertEquals(day2, period2.startDate)
        val task2 = taskOf(period2.id)
        val round2 = progress.ensureCountSession(orderId).session

        val tooEarly = runCatching { submit(task2.id, round2.id, "10", day1) }
            .exceptionOrNull()
        assertEquals("OUT_OF_DATE_WINDOW", (tooEarly as ProgressCommandException).code)
        assertTrue(progress.progressHistory(task2.id).isEmpty())

        val today = submit(task2.id, round2.id, "10", day2)
        assertTrue(today is ProgressCommandResult.Applied)
    }
}
