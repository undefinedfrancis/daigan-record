package com.daigan.record.data.repository

import androidx.test.core.app.ApplicationProvider
import com.daigan.record.data.local.toModel
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.shanghaiRules
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R3-1 / R3-2: cumulative eligibility and count-period behaviour on a **real count order**
 * (real Room, real repositories). Short-order fixtures are deliberately not used here:
 * they cannot express a round, its period, or its own target.
 */
class CountPeriodProgressTest {

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

    private suspend fun seedAccount(): Pair<String, String> {
        val (owner, account) = env.seedOwnerAndAccount()
        return owner.id to account.id
    }

    private fun countDraft(
        ownerId: String,
        accountId: String,
        target: Int,
        tasks: List<TaskDraft>,
        priceYuan: String = "100.00",
    ): HostingOrderDraft = HostingOrderDraft(
        ownerId = ownerId,
        accountId = accountId,
        title = "打卡托管",
        startDate = day1,
        endDate = day1.plusDays(29),
        priceYuan = priceYuan,
        paymentMode = PaymentMode.ONE_TIME,
        refresh = shanghaiRules,
        tasks = tasks,
        hostingMode = HostingMode.COUNT_BASED,
        targetSessions = target,
        creationOperationId = UUID.randomUUID().toString(),
    )

    private fun checkbox(title: String = "签到") = TaskDraft(
        title = title,
        progressType = ProgressType.CHECKBOX,
        recurrence = RecurrenceType.DAILY,
    )

    private fun cumulative(title: String = "累计抽数", target: String = "100") = TaskDraft(
        title = title,
        progressType = ProgressType.NUMERIC,
        numericMode = NumericMode.CUMULATIVE,
        startValue = "0",
        targetValue = target,
        recurrence = RecurrenceType.DAILY,
    )

    private suspend fun createCountOrder(
        target: Int = 3,
        tasks: List<TaskDraft> = listOf(checkbox()),
    ): String {
        val (ownerId, accountId) = seedAccount()
        return env.orderRepository.createHostingOrder(countDraft(ownerId, accountId, target, tasks))
    }

    private suspend fun submit(
        taskId: String,
        sessionId: String,
        total: String,
        date: LocalDate = day1,
        operationId: String = UUID.randomUUID().toString(),
    ): ProgressCommandResult = progress.submitTotal(
        SubmitTotalCommand(
            operationId = operationId,
            taskId = taskId,
            scope = ProgressScope.Session(sessionId),
            attributedGameDate = date,
            total = BigDecimal(total),
        ),
    )

    private suspend fun session(orderId: String, index: Int) = checkNotNull(
        env.database.hostingSessionDao().findByIndex(orderId, index),
    )

    private suspend fun taskIds(orderId: String, periodId: String) =
        env.database.taskDao().findForPeriod(periodId).filter { it.retiredAt == null }

    private fun advanceTo(date: LocalDate) {
        // 10:00 Shanghai on that game date (after the 04:00 daily refresh).
        env.clock.set(Instant.parse("${date}T02:00:00Z"))
    }

    // ---------------------------------------------------------------- R3-1

    @Test
    fun creationDaySubmissionUsesTheFirstSessionScope() = runBlocking {
        val orderId = createCountOrder(target = 2, tasks = listOf(cumulative(target = "50")))
        val task = taskIds(orderId, env.database.orderDao().findPeriods(orderId).single().id).single()
        val first = session(orderId, 1)

        val applied = submit(task.id, first.id, "10")

        assertTrue(applied is ProgressCommandResult.Applied)
        assertEquals(ProgressScope.Session(first.id), applied.event.scope)
        assertEquals(1, progress.progressHistory(task.id).size)
    }

    /**
     * The count period's endDate is the creation anchor, not a service deadline. Day 2
     * writing into the same unfinished round must be accepted.
     */
    @Test
    fun nextGameDaySubmissionToSameUnfinishedSessionIgnoresTheAnchorAsDeadline() = runBlocking {
        val orderId = createCountOrder(target = 2, tasks = listOf(cumulative(target = "100")))
        val task = taskIds(orderId, env.database.orderDao().findPeriods(orderId).single().id).single()
        val first = session(orderId, 1)
        val period = env.database.orderDao().findPeriods(orderId).single()
        assertEquals("container anchor is a single day", period.startDate, period.endDate)

        submit(task.id, first.id, "10", date = day1)
        advanceTo(day2)
        submit(task.id, first.id, "25", date = day2)

        val status = checkNotNull(progress.cumulativeStatus(task.id, ProgressScope.Session(first.id)))
        assertEquals(0, status.total.compareTo(BigDecimal("25")))
        assertEquals(2, progress.progressHistory(task.id).size)
    }

    /** CUMULATIVE is independent of the recurrence trigger day. */
    @Test
    fun nonTriggerDayIsAcceptedUnderCumulativeMode() = runBlocking {
        val mondayOnly = cumulative(target = "100").copy(
            recurrence = RecurrenceType.SELECTED_WEEKDAYS,
            weekdays = setOf(DayOfWeek.MONDAY),
        )
        val orderId = createCountOrder(target = 2, tasks = listOf(mondayOnly))
        val task = taskIds(orderId, env.database.orderDao().findPeriods(orderId).single().id).single()
        val first = session(orderId, 1)
        // 2026-10-17 is a Saturday.
        assertEquals(DayOfWeek.SATURDAY, day1.dayOfWeek)

        submit(task.id, first.id, "10", date = day1)

        assertEquals(1, progress.progressHistory(task.id).size)
    }

    @Test
    fun taskActiveUntilRejectsLaterGameDate() = runBlocking {
        val limited = cumulative(target = "100").copy(activeUntil = day1.toString())
        val orderId = createCountOrder(target = 2, tasks = listOf(limited))
        val task = taskIds(orderId, env.database.orderDao().findPeriods(orderId).single().id).single()
        val first = session(orderId, 1)
        advanceTo(day2)

        val error = runCatching {
            submit(task.id, first.id, "10", date = day2)
        }.exceptionOrNull()

        assertEquals("OUT_OF_DATE_WINDOW", (error as ProgressCommandException).code)
        assertTrue(progress.progressHistory(task.id).isEmpty())
    }

    @Test
    fun futureGameDateIsRejectedForCountSessions() = runBlocking {
        val orderId = createCountOrder(target = 2, tasks = listOf(cumulative()))
        val task = taskIds(orderId, env.database.orderDao().findPeriods(orderId).single().id).single()
        val first = session(orderId, 1)

        val error = runCatching { submit(task.id, first.id, "10", date = day2) }.exceptionOrNull()

        assertEquals("FUTURE_GAME_DATE", (error as ProgressCommandException).code)
    }

    @Test
    fun historicalCorrectionKeepsAttributionAndAppends() = runBlocking {
        val orderId = createCountOrder(target = 2, tasks = listOf(cumulative(target = "100")))
        val task = taskIds(orderId, env.database.orderDao().findPeriods(orderId).single().id).single()
        val first = session(orderId, 1)
        val applied = submit(task.id, first.id, "10", date = day1) as ProgressCommandResult.Applied
        advanceTo(day2)

        progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = task.id,
                targetEventId = applied.event.id,
                total = BigDecimal("40"),
            ),
        )

        val events = progress.progressHistory(task.id)
        assertEquals(2, events.size)
        assertEquals(day1, events.last().gameDate)
        assertEquals(applied.event.id, events.last().correctsEventId)
    }

    @Test
    fun retryAfterAdvancementReplaysTheOriginalScope() = runBlocking {
        val orderId = createCountOrder(target = 3, tasks = listOf(cumulative(target = "5")))
        val period = env.database.orderDao().findPeriods(orderId).single()
        val task = taskIds(orderId, period.id).single()
        val first = session(orderId, 1)
        val operationId = "retry-${UUID.randomUUID()}"
        submit(task.id, first.id, "5", operationId = operationId)
        assertNotNull(first.let { session(orderId, 1).completedAt })

        // The order advanced to a new round; the retry still targets its original scope.
        val advanced = progress.ensureCountSession(orderId).session
        assertEquals(2, advanced.sessionIndex)

        val replay = submit(task.id, first.id, "5", operationId = operationId)

        assertTrue(replay is ProgressCommandResult.IdempotentReplay)
        assertEquals(1, progress.progressHistory(task.id).count { it.operationId == operationId })
    }

    @Test
    fun completedSessionRejectsNewSubmissionInsteadOfRedirecting() = runBlocking {
        val orderId = createCountOrder(target = 3, tasks = listOf(cumulative(target = "5")))
        val period = env.database.orderDao().findPeriods(orderId).single()
        val task = taskIds(orderId, period.id).single()
        val first = session(orderId, 1)
        submit(task.id, first.id, "5")
        val second = progress.ensureCountSession(orderId).session
        assertEquals(2, second.sessionIndex)

        val error = runCatching { submit(task.id, first.id, "8", date = day1) }.exceptionOrNull()

        assertEquals("SESSION_COMPLETED", (error as ProgressCommandException).code)
        assertEquals(1, progress.progressHistory(task.id).size)
        assertTrue(
            "new work must belong to round 2, not be redirected silently",
            progress.progressHistory(task.id).none { it.scope == ProgressScope.Session(second.id) },
        )
    }

    // ---------------------------------------------------------------- R3-2

    @Test
    fun createAssignsThePeriodItsOwnTarget() = runBlocking {
        val orderId = createCountOrder(target = 4)
        val period = env.database.orderDao().findPeriods(orderId).single()
        assertEquals(4, period.serviceCount)
        assertEquals(1, env.database.hostingSessionDao().countForOrder(orderId))
    }

    @Test
    fun twoPeriodsKeepDifferentTargetsRostersAndRounds() = runBlocking {
        val (ownerId, accountId) = seedAccount()
        val orderId = env.orderRepository.createHostingOrder(
            countDraft(
                ownerId = ownerId,
                accountId = accountId,
                target = 2,
                tasks = listOf(checkbox("签到"), cumulative("抽数", "10")),
                priceYuan = "100.00",
            ),
        )
        val firstPeriod = env.database.orderDao().findPeriods(orderId).single()
        val firstTasks = taskIds(orderId, firstPeriod.id)
        val checkboxTask = firstTasks.first { it.progressType == ProgressType.CHECKBOX }
        val cumulativeTask = firstTasks.first { it.numericMode == NumericMode.CUMULATIVE }
        val round1 = session(orderId, 1)

        // Round 1: mixed ordinary + cumulative.
        env.orderRepository.setOccurrenceCompleted(checkboxTask.id, true, CountHostingKey.of(1))
        assertNull("ordinary alone must not complete a mixed round", session(orderId, 1).completedAt)
        submit(cumulativeTask.id, round1.id, "10")
        assertNotNull(session(orderId, 1).completedAt)

        // Round 2 on the same game day. A brand-new round row is ensured explicitly,
        // then its own scope is written; round 1's scope is never reused.
        val round2Row = progress.ensureCountSession(orderId).session
        assertEquals(2, round2Row.sessionIndex)
        env.orderRepository.setOccurrenceCompleted(checkboxTask.id, true, CountHostingKey.of(2))
        submit(cumulativeTask.id, round2Row.id, "10")
        assertNotNull("two rounds complete on one game day", session(orderId, 2).completedAt)
        assertEquals(2, CountHostingCompleted.count(orderId, env))

        // Renewal: new period, own target/price/roster, history preserved.
        val renewal = env.orderRepository.renewCountHosting(
            orderId = orderId,
            operationId = "renew-${UUID.randomUUID()}",
            targetSessions = 1,
            priceCents = 5000,
        )
        assertTrue(renewal.countBased)
        assertEquals(1, renewal.targetSessions)
        val secondPeriod = env.database.orderDao().findPeriods(orderId).first { it.id == renewal.periodId }
        assertEquals(1, secondPeriod.serviceCount)
        assertEquals(5000, secondPeriod.priceCents)
        assertEquals("previous period is untouched", firstPeriod.serviceCount, 2)
        assertEquals("no fabricated deadline", secondPeriod.startDate, secondPeriod.endDate)

        // The new period's roster is edited independently: remove the cumulative task.
        val secondTasks = taskIds(orderId, secondPeriod.id)
        val kept = secondTasks.first { it.progressType == ProgressType.CHECKBOX }
        env.orderRepository.updateTaskDetails(
            orderId,
            listOf(
                TaskDraft(
                    id = kept.id,
                    periodId = secondPeriod.id,
                    title = kept.title,
                    progressType = kept.progressType,
                    recurrence = kept.recurrence,
                ),
            ),
        )
        // F2: the removed task belongs to the roster of the day it was retired, so the next
        // round is recorded on the following game day — where it is genuinely not due.
        env.clock.set(Instant.parse("2026-10-18T02:00:00Z"))
        val round3 = progress.ensureCountSession(orderId).session
        assertEquals(3, round3.sessionIndex)
        assertEquals(secondPeriod.id, round3.periodId)
        env.orderRepository.setOccurrenceCompleted(kept.id, true, CountHostingKey.of(3))

        assertEquals(3, CountHostingCompleted.count(orderId, env))
        assertNotNull(
            "every period target met",
            env.database.orderDao().findById(orderId)?.completedAt,
        )

        // Receipt allocation: a payment recorded against the new period stays there.
        env.receiptRepository.recordPayment(
            PaymentRequest(
                orderId = orderId,
                periodId = secondPeriod.id,
                amountCents = 5000,
                receivedAt = env.clock.now(),
                channel = PaymentChannel.WECHAT,
                note = "第二期收款",
                operationId = "pay-${UUID.randomUUID()}",
            ),
        )
        val receipts = env.database.receiptDao().findForOrder(orderId)
        assertEquals(1, receipts.count { it.periodId == secondPeriod.id })
        assertTrue(receipts.filter { it.periodId == secondPeriod.id }.all { it.amountCents == 5000L })
    }

    @Test
    fun emptyDueSetNeverCompletesARound() = runBlocking {
        val future = cumulative(target = "5").copy(activeUntil = day1.toString())
        val orderId = createCountOrder(target = 2, tasks = listOf(future))
        val period = env.database.orderDao().findPeriods(orderId).single()
        val task = taskIds(orderId, period.id).single()

        // Recorded on a date before the task is active: the round has no due task.
        advanceTo(day3)
        env.orderRepository.setOccurrenceCompleted(task.id, true, CountHostingKey.of(1))

        assertNull(
            "an empty due set must not fabricate a completed round",
            session(orderId, 1).completedAt,
        )
        assertEquals(0, CountHostingCompleted.count(orderId, env))
    }

    @Test
    fun reopeningAnEarlierRoundKeepsLaterRoundsAndCaches() = runBlocking {
        val orderId = createCountOrder(target = 2, tasks = listOf(checkbox()))
        val period = env.database.orderDao().findPeriods(orderId).single()
        val task = taskIds(orderId, period.id).single()
        env.orderRepository.setOccurrenceCompleted(task.id, true, CountHostingKey.of(1))
        env.orderRepository.setOccurrenceCompleted(task.id, true, CountHostingKey.of(2))
        assertEquals(2, CountHostingCompleted.count(orderId, env))
        assertNotNull(env.database.orderDao().findById(orderId)?.completedAt)

        env.orderRepository.reopenCountSession(orderId, 1)

        assertNull(session(orderId, 1).completedAt)
        assertNotNull("later round is untouched", session(orderId, 2).completedAt)
        assertEquals(1, CountHostingCompleted.count(orderId, env))
        assertNull(env.database.orderDao().findById(orderId)?.completedAt)
    }

    @Test
    fun targetChangeDoesNotRenumberSessions() = runBlocking {
        val orderId = createCountOrder(target = 2, tasks = listOf(checkbox()))
        val period = env.database.orderDao().findPeriods(orderId).single()
        val task = taskIds(orderId, period.id).single()
        env.orderRepository.setOccurrenceCompleted(task.id, true, CountHostingKey.of(1))
        // The next index is allocated lazily: ensure it before asserting its identity.
        assertEquals(2, progress.ensureCountSession(orderId).session.sessionIndex)

        progress.updateCountTarget(orderId, period.id, 5)

        assertEquals(5, env.database.orderDao().findPeriod(period.id)?.serviceCount)
        assertEquals(1, session(orderId, 1).sessionIndex)
        assertEquals(2, session(orderId, 2).sessionIndex)
        assertEquals(2, env.database.hostingSessionDao().countForOrder(orderId))
    }

    @Test
    fun concurrentEnsureNeverDuplicatesAnIndex() = runBlocking {
        val orderId = createCountOrder(target = 3, tasks = listOf(checkbox()))
        val period = env.database.orderDao().findPeriods(orderId).single()
        val task = taskIds(orderId, period.id).single()
        env.orderRepository.setOccurrenceCompleted(task.id, true, CountHostingKey.of(1))

        val results = listOf(
            async(Dispatchers.IO) {
                progress.ensureCountSession(orderId, 2)
            },
            async(Dispatchers.IO) {
                progress.ensureCountSession(orderId, 2)
            },
        ).map { it.await() }

        assertEquals(setOf(2), results.map { it.session.sessionIndex }.toSet())
        assertEquals(1, env.database.hostingSessionDao().findForOrder(orderId).count { it.sessionIndex == 2 })
        assertNotEquals(0, results.count { it.created })
        assertEquals(1, results.count { it.created })
    }

    @Test
    fun completingOrdinaryWorkAfterCumulativeReachedDoesNotReopenTheRound() = runBlocking {
        val orderId = createCountOrder(
            target = 2,
            tasks = listOf(checkbox("签到"), cumulative("抽数", "10")),
        )
        val period = env.database.orderDao().findPeriods(orderId).single()
        val tasks = taskIds(orderId, period.id)
        val checkboxTask = tasks.first { it.progressType == ProgressType.CHECKBOX }
        val cumulativeTask = tasks.first { it.numericMode == NumericMode.CUMULATIVE }
        val round = session(orderId, 1)

        submit(cumulativeTask.id, round.id, "10")
        assertNull("cumulative alone does not complete a mixed round", session(orderId, 1).completedAt)
        env.orderRepository.setOccurrenceCompleted(checkboxTask.id, true, CountHostingKey.of(1))

        assertNotNull(session(orderId, 1).completedAt)
        assertEquals(1, CountHostingCompleted.count(orderId, env))
        assertFalse("round 1 must stay counted", session(orderId, 1).completedAt == null)
    }
}

/** Occurrence keys of count rounds, kept local to the test. */
private object CountHostingKey {
    fun of(index: Int): String = "N:$index"
}

private object CountHostingCompleted {
    suspend fun count(orderId: String, env: OrderTestEnvironment): Int =
        com.daigan.record.data.model.CountHosting.completedCount(
            env.database.hostingSessionDao().findForOrder(orderId)
                .map { it.toModel() },
        )
}
