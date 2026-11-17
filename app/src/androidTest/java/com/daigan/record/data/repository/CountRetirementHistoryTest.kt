package com.daigan.record.data.repository

import androidx.test.core.app.ApplicationProvider
import com.daigan.record.data.local.toModel
import com.daigan.record.data.model.CountHosting
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressEventKind
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
 * Stage-3 repair F2: a historical round's due set is the period roster **resolved at that
 * round's own attributed game date**, retirement included. `task_definitions.retiredAt`
 * removes a task from later dates only, exactly like the restore reconstructor, so a task
 * retired today still had to be done in yesterday's round.
 *
 * Every case drives real repository commands and Room; nothing asserts merely that a filter
 * disappeared.
 */
class CountRetirementHistoryTest {

    private lateinit var env: OrderTestEnvironment
    private lateinit var progress: CumulativeProgressRepository

    private val day1 = LocalDate.parse("2026-10-17")
    private val day2 = LocalDate.parse("2026-10-18")
    private val day3 = LocalDate.parse("2026-10-19")
    private val day4 = LocalDate.parse("2026-10-20")

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

    private fun checkbox(title: String, activeFrom: String = "", activeUntil: String = "") =
        TaskDraft(
            title = title,
            progressType = ProgressType.CHECKBOX,
            recurrence = RecurrenceType.DAILY,
            activeFrom = activeFrom,
            activeUntil = activeUntil,
        )

    private fun cumulative(title: String = "累计抽数", target: String = "100") = TaskDraft(
        title = title,
        progressType = ProgressType.NUMERIC,
        numericMode = NumericMode.CUMULATIVE,
        startValue = "0",
        targetValue = target,
        recurrence = RecurrenceType.DAILY,
    )

    /** 普通数值任务（非累计）：每轮各自记录进度，达标即完成。 */
    private fun ordinaryNumeric(title: String = "刷本", target: String = "100") = TaskDraft(
        title = title,
        progressType = ProgressType.NUMERIC,
        numericMode = NumericMode.PER_OCCURRENCE,
        startValue = "0",
        targetValue = target,
        recurrence = RecurrenceType.DAILY,
    )

    private suspend fun createCountOrder(target: Int, tasks: List<TaskDraft>): String {
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
                tasks = tasks,
                hostingMode = HostingMode.COUNT_BASED,
                targetSessions = target,
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
    }

    private suspend fun period1(orderId: String) =
        env.database.orderDao().findPeriods(orderId).single()

    private suspend fun round(orderId: String, index: Int) = checkNotNull(
        env.database.hostingSessionDao().findByIndex(orderId, index),
    )

    private fun advanceTo(date: LocalDate) {
        env.clock.set(Instant.parse("${date}T02:00:00Z"))
    }

    /** Retires every task of [periodId] that is not [kept], through the product command. */
    private suspend fun retireAllExcept(
        orderId: String,
        periodId: String,
        kept: List<com.daigan.record.data.local.entity.TaskDefinitionEntity>,
    ) {
        env.orderRepository.updateTaskDetails(
            orderId,
            kept.map { task ->
                TaskDraft(
                    id = task.id,
                    periodId = periodId,
                    title = task.title,
                    progressType = task.progressType,
                    recurrence = task.recurrence,
                    numericMode = task.numericMode,
                )
            },
        )
        assertTrue(env.database.taskDao().findForPeriod(periodId).any { it.retiredAt != null })
    }

    private suspend fun completedRounds(orderId: String): Int =
        CountHosting.completedCount(env.database.hostingSessionDao().findForOrder(orderId).map { it.toModel() })

    // ---------------------------------------------------------------- F2

    /**
     * Round 1 was left unfinished, then its unfinished task is retired the next day. Round 1
     * must not become complete: at its own attributed date the task was still due.
     */
    @Test
    fun unfinishedTaskRetiredLaterNeverCompletesTheEarlierRound() = runBlocking {
        val orderId = createCountOrder(3, listOf(checkbox("签到"), checkbox("额外任务")))
        val period = period1(orderId)
        val tasks = env.database.taskDao().findForPeriod(period.id)
        val signIn = tasks.first { it.title == "签到" }
        val extra = tasks.first { it.title == "额外任务" }

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        assertNull("one of two due tasks is missing", round(orderId, 1).completedAt)

        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))
        // A real re-tap of the already completed task: the shared service re-derives rounds.
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")

        assertNull(
            "the retirement day is after the round, so the task was due in that round",
            round(orderId, 1).completedAt,
        )
        assertEquals(0, completedRounds(orderId))
        assertNull(env.database.orderDao().findById(orderId)?.completedAt)
        assertNotNull("the retired row is kept as history", env.database.taskDao().findById(extra.id)?.retiredAt)
    }

    /** Retirement on the round's own date follows the same boundary: still due that day. */
    @Test
    fun retirementOnTheRoundDateKeepsTheTaskDue() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), checkbox("额外任务")))
        val period = period1(orderId)
        val tasks = env.database.taskDao().findForPeriod(period.id)
        val signIn = tasks.first { it.title == "签到" }

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        retireAllExcept(orderId, period.id, listOf(signIn))
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")

        assertNull(
            "retiring today does not rewrite today's due set",
            round(orderId, 1).completedAt,
        )
        assertEquals(0, completedRounds(orderId))
    }

    /** A round that was validly complete before the retirement keeps its historical result. */
    @Test
    fun roundCompletedBeforeRetirementKeepsItsResult() = runBlocking {
        val orderId = createCountOrder(3, listOf(checkbox("签到"), checkbox("额外任务")))
        val period = period1(orderId)
        val tasks = env.database.taskDao().findForPeriod(period.id)
        val signIn = tasks.first { it.title == "签到" }
        val extra = tasks.first { it.title == "额外任务" }

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        env.orderRepository.setOccurrenceCompleted(extra.id, true, "N:1")
        val completedAt = checkNotNull(round(orderId, 1).completedAt)
        assertEquals(1, completedRounds(orderId))
        val periodBefore = period1(orderId)

        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))
        // Another round is written the next day; the reconciliation re-derives round 1 from
        // its own evidence, which the retirement did not touch.
        val round2 = progress.ensureCountSession(orderId).session
        assertEquals(2, round2.sessionIndex)
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:2")

        assertEquals("the historical completion instant is unchanged", completedAt, round(orderId, 1).completedAt)
        assertEquals(1, completedRounds(orderId))
        assertNull("round 2 still owes the retired-today task", round(orderId, 2).completedAt)
        val periodAfter = period1(orderId)
        assertEquals(periodBefore.serviceCount, periodAfter.serviceCount)
        assertEquals(periodBefore.priceCents, periodAfter.priceCents)
        assertEquals(periodBefore.startDate, periodAfter.startDate)
        assertEquals("only the allocated round row was added", 2, env.database.hostingSessionDao().countForOrder(orderId))
    }

    /** A task retired before the round's date is not part of that round's due set. */
    @Test
    fun taskRetiredBeforeTheRoundDateIsExcluded() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), checkbox("额外任务")))
        val period = period1(orderId)
        val signIn = env.database.taskDao().findForPeriod(period.id).first { it.title == "签到" }

        retireAllExcept(orderId, period.id, listOf(signIn))
        advanceTo(day2)
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")

        assertNotNull("the retired task was not due on the later round date", round(orderId, 1).completedAt)
        assertEquals(1, completedRounds(orderId))
    }

    /** `activeFrom`/`activeUntil` decide the due set at the attributed date. */
    @Test
    fun taskActiveWindowDecidesTheDueSetAtTheRoundDate() = runBlocking {
        val orderId = createCountOrder(
            2,
            listOf(
                checkbox("签到"),
                checkbox("后加任务", activeFrom = day2.toString()),
                checkbox("限时任务", activeUntil = day1.toString()),
            ),
        )
        val period = period1(orderId)
        val tasks = env.database.taskDao().findForPeriod(period.id)
        val signIn = tasks.first { it.title == "签到" }
        val later = tasks.first { it.title == "后加任务" }
        val limited = tasks.first { it.title == "限时任务" }

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        env.orderRepository.setOccurrenceCompleted(limited.id, true, "N:1")
        assertNotNull(
            "the not-yet-active task is not due on the first round date",
            round(orderId, 1).completedAt,
        )

        // Round 2 is written on day 2: the expired task is no longer due, the later one is.
        advanceTo(day2)
        val round2 = progress.ensureCountSession(orderId).session
        assertEquals(2, round2.sessionIndex)
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:2")
        assertNull("the newly active task is due and still missing", round(orderId, 2).completedAt)
        env.orderRepository.setOccurrenceCompleted(later.id, true, "N:2")
        assertNotNull(round(orderId, 2).completedAt)
        assertEquals(2, completedRounds(orderId))
    }

    /**
     * Mixed ordinary + cumulative round: retiring the cumulative task later must not let the
     * ordinary completion alone close the round, and the present-day row list still hides
     * the retired task (UI filtering is a separate concern from historical truth).
     */
    @Test
    fun retiredCumulativeTaskKeepsItsHistoricalRoundOpenAndLeavesTheUiClean() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), cumulative("抽数", "100")))
        val period = period1(orderId)
        val tasks = env.database.taskDao().findForPeriod(period.id)
        val signIn = tasks.first { it.progressType == ProgressType.CHECKBOX }
        val cumulativeTask = tasks.first { it.numericMode == NumericMode.CUMULATIVE }
        val round1 = round(orderId, 1)

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                scope = ProgressScope.Session(round1.id),
                attributedGameDate = day1,
                total = BigDecimal("50"),
            ),
        )
        assertNull("below target", round(orderId, 1).completedAt)

        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")

        assertNull(
            "the retired cumulative task was due and never reached its target",
            round(orderId, 1).completedAt,
        )
        assertEquals(0, completedRounds(orderId))

        val rows = progress.resolveCountRoundProgress(orderId, period.id, 1)
        assertEquals("only current tasks are listed for the user", listOf(signIn.id), rows.map { it.taskId })
        assertEquals(
            "the retired task keeps its event history",
            1,
            progress.progressHistory(cumulativeTask.id).size,
        )
    }

    /** Other rounds of the same period are re-derived independently. */
    @Test
    fun laterRoundsAndOtherPeriodsRemainUnchanged() = runBlocking {
        val orderId = createCountOrder(3, listOf(checkbox("签到"), checkbox("额外任务")))
        val period = period1(orderId)
        val tasks = env.database.taskDao().findForPeriod(period.id)
        val signIn = tasks.first { it.title == "签到" }
        val extra = tasks.first { it.title == "额外任务" }

        // Round 1: complete, so the order can advance.
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        env.orderRepository.setOccurrenceCompleted(extra.id, true, "N:1")
        val round1CompletedAt = checkNotNull(round(orderId, 1).completedAt)

        // Round 2 on day 2 starts and is left open (no work yet).
        advanceTo(day2)
        val round2 = progress.ensureCountSession(orderId).session
        assertEquals(2, round2.sessionIndex)

        // The retirement happens on day 2, so round 2 still has the task due.
        retireAllExcept(orderId, period.id, listOf(signIn))
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:2")

        assertNull("round 2 is attributed to the retirement day", round(orderId, 2).completedAt)
        assertEquals("round 1 is untouched", round1CompletedAt, round(orderId, 1).completedAt)
        assertEquals(1, completedRounds(orderId))
        assertEquals(2, env.database.hostingSessionDao().countForOrder(orderId))
    }

    // ---------------------------------------------------------- H1 recovery

    /**
     * The reported stuck state, then the actual recovery: the retired task's own history
     * can correct its existing observation, and the derived round/completion recompute
     * through the shared rules.
     */
    @Test
    fun retiredCumulativeObservationIsCorrectableThroughItsHistory() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), cumulative("抽数", "100")))
        val period = period1(orderId)
        val signIn = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        val cumulativeTask = env.database.taskDao().findForPeriod(period.id)
            .first { it.numericMode == NumericMode.CUMULATIVE }
        val round1 = round(orderId, 1)

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        val original = progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                scope = ProgressScope.Session(round1.id),
                attributedGameDate = day1,
                total = BigDecimal("50"),
            ),
        ).event

        // The confirmed state before any correction.
        assertNull(round(orderId, 1).completedAt)
        assertEquals(0, completedRounds(orderId))

        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))
        assertNull("the historical round stays open", round(orderId, 1).completedAt)
        assertEquals(
            "the retired task is out of the current list, not out of history",
            listOf(signIn.id),
            progress.resolveCountRoundProgress(orderId, period.id, 1).map { it.taskId },
        )
        val before = progress.countSessionHistory(orderId).single { it.session.sessionIndex == 1 }
        assertEquals("the historical roster still names it", 2, before.taskRows.size + before.retiredDueTasks.size)
        assertTrue(before.retiredDueTasks.any { it.taskId == cumulativeTask.id && !it.completed })
        assertEquals("no observation is erased", 1, progress.progressHistory(cumulativeTask.id).size)

        // Recovery: correct the existing observation to the real total. Task, scope, round
        // and attributed date are inherited from the target event and cannot be moved.
        val correction = progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                targetEventId = original.id,
                total = BigDecimal("100"),
            ),
        ).event

        assertEquals(ProgressEventKind.CORRECT, correction.kind)
        assertEquals(original.id, correction.correctsEventId)
        assertEquals("the attribution stays on the original round", day1, correction.gameDate)
        assertEquals(ProgressScope.Session(round1.id), correction.scope)

        // Append-only: the original observation survives alongside the correction.
        val history = progress.progressHistory(cumulativeTask.id)
        assertEquals(2, history.size)
        assertEquals(ProgressEventKind.SUBMIT, history.first { it.id == original.id }.kind)
        assertEquals(BigDecimal("50"), history.first { it.id == original.id }.totalValue)
        assertEquals(BigDecimal("100"), history.first { it.id == correction.id }.totalValue)

        assertNotNull("the historical round is complete again", round(orderId, 1).completedAt)
        assertEquals(1, completedRounds(orderId))
        assertEquals(
            0,
            BigDecimal("100").compareTo(
                checkNotNull(progress.cumulativeStatus(cumulativeTask.id, ProgressScope.Session(round1.id))).total,
            ),
        )
        // The same historical view the UI reads now reports the task as done.
        val after = progress.countSessionHistory(orderId).single { it.session.sessionIndex == 1 }
        assertTrue(after.retiredDueTasks.any { it.taskId == cumulativeTask.id && it.completed })
    }

    /**
     * Retraction is the other side of the same narrow route: it reopens the round instead
     * of leaving fabricated progress behind, and it never deletes history.
     */
    @Test
    fun retiredCumulativeCorrectionCanBeRetracted() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), cumulative("抽数", "100")))
        val period = period1(orderId)
        val signIn = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        val cumulativeTask = env.database.taskDao().findForPeriod(period.id)
            .first { it.numericMode == NumericMode.CUMULATIVE }
        val round1 = round(orderId, 1)

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        val original = progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                scope = ProgressScope.Session(round1.id),
                attributedGameDate = day1,
                total = BigDecimal("50"),
            ),
        ).event
        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))
        val correction = progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                targetEventId = original.id,
                total = BigDecimal("100"),
            ),
        ).event
        assertEquals(1, completedRounds(orderId))

        // An event has one successor only: the corrected original cannot be retracted as a
        // second move of the same observation.
        val doubleInheritance = runCatching {
            progress.correctTotal(
                CorrectTotalCommand(
                    operationId = UUID.randomUUID().toString(),
                    taskId = cumulativeTask.id,
                    targetEventId = original.id,
                    total = null,
                ),
            )
        }.exceptionOrNull()
        assertNotNull("an already corrected event has no second successor", doubleInheritance)

        // Retract the effective observation instead: the round reopens.
        val retraction = progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                targetEventId = correction.id,
                total = null,
            ),
        ).event
        assertEquals(ProgressEventKind.RETRACT, retraction.kind)
        assertEquals(correction.id, retraction.correctsEventId)
        assertEquals(day1, retraction.gameDate)

        assertNull("no fabricated progress remains", round(orderId, 1).completedAt)
        assertEquals(0, completedRounds(orderId))
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(
                checkNotNull(progress.cumulativeStatus(cumulativeTask.id, ProgressScope.Session(round1.id))).total,
            ),
        )
        // Append-only: submit + correct + retract are all still readable.
        val history = progress.progressHistory(cumulativeTask.id)
        assertEquals(3, history.size)
    }

    /**
     * The adjacent case (no observation at all) is not solved by correcting an
     * observation, so it has its own explicit recovery: the historical total may be
     * recorded for that round's own game date. Nothing is completed automatically — the
     * user states the real total, and a date after the retirement is refused.
     */
    @Test
    fun retiredCumulativeTaskWithoutObservationIsRecoverableAtItsHistoricalDate() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), cumulative("抽数", "100")))
        val period = period1(orderId)
        val signIn = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        val cumulativeTask = env.database.taskDao().findForPeriod(period.id)
            .first { it.numericMode == NumericMode.CUMULATIVE }
        val round1 = round(orderId, 1)

        // Only the checkbox is observed: the cumulative task has no observation at all.
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))
        assertNull(round(orderId, 1).completedAt)
        assertEquals(0, completedRounds(orderId))
        val before = progress.countSessionHistory(orderId).single { it.session.sessionIndex == 1 }
        assertTrue(before.retiredDueTasks.any { it.taskId == cumulativeTask.id && !it.hasObservation })

        // Post-retirement progress is still refused: the boundary is the retirement day.
        val futureProgress = runCatching {
            progress.submitTotal(
                SubmitTotalCommand(
                    operationId = UUID.randomUUID().toString(),
                    taskId = cumulativeTask.id,
                    scope = ProgressScope.Session(round1.id),
                    attributedGameDate = day3,
                    total = BigDecimal("100"),
                ),
            )
        }.exceptionOrNull()
        val failure = futureProgress as? ProgressCommandException
        assertNotNull("the write must be rejected", futureProgress)
        assertEquals("ORDER_NOT_WRITABLE", failure?.code)
        assertTrue(
            "the rejection names the retirement boundary",
            checkNotNull(failure?.message).contains("移除"),
        )

        // The explicit historical record: the total for the round's own date.
        val historicalDate = checkNotNull(
            progress.countSessionHistory(orderId).single { it.session.sessionIndex == 1 }.attributedDate,
        )
        assertEquals("the round is attributed to day 1", day1, historicalDate)
        val recorded = progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                scope = ProgressScope.Session(round1.id),
                attributedGameDate = historicalDate,
                total = BigDecimal("100"),
            ),
        ).event
        assertEquals(historicalDate, recorded.gameDate)
        assertNotNull(round(orderId, 1).completedAt)
        assertEquals(1, completedRounds(orderId))

        // Scope and attribution cannot be forged into another order's round.
        val otherOrder = createCountOrder(1, listOf(checkbox("其他订单的签到")))
        val foreignSession = round(otherOrder, 1).id
        val foreignScope = runCatching {
            progress.submitTotal(
                SubmitTotalCommand(
                    operationId = UUID.randomUUID().toString(),
                    taskId = cumulativeTask.id,
                    scope = ProgressScope.Session(foreignSession),
                    attributedGameDate = historicalDate,
                    total = BigDecimal("100"),
                ),
            )
        }.exceptionOrNull()
        assertNotNull("another order's round is a different observation", foreignScope)
    }

    /**
     * The checkbox twin of the same stuck state: a retired due task without any
     * observation keeps its round open, and exactly that round can be corrected.
     */
    @Test
    fun retiredCheckboxTaskWithoutObservationIsRecoverableInItsOwnRound() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), checkbox("额外任务")))
        val period = period1(orderId)
        val signIn = env.database.taskDao().findForPeriod(period.id).first { it.title == "签到" }
        val extra = env.database.taskDao().findForPeriod(period.id).first { it.title == "额外任务" }

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))
        assertNull("the round owes the retired task", round(orderId, 1).completedAt)
        assertEquals(0, completedRounds(orderId))

        // A still-active task is not a historical correction target.
        val activeTarget = runCatching {
            env.orderRepository.setHistoricalRoundCompletion(orderId, 1, signIn.id, true)
        }.exceptionOrNull()
        assertNotNull("the current list handles active tasks", activeTarget)

        env.orderRepository.setHistoricalRoundCompletion(orderId, 1, extra.id, true)
        assertNotNull("the historical round is complete again", round(orderId, 1).completedAt)
        assertEquals(1, completedRounds(orderId))

        // Retraction reopens the same round; the row is kept, nothing is deleted.
        env.orderRepository.setHistoricalRoundCompletion(orderId, 1, extra.id, false)
        assertNull(round(orderId, 1).completedAt)
        assertEquals(0, completedRounds(orderId))
        assertEquals(1, env.database.hostingSessionDao().countForOrder(orderId))

        // A cumulative task is never toggled through this command; its history is the
        // event chain, and that correction stays in the cumulative history panel.
        val cumulativeOrder = createCountOrder(2, listOf(cumulative("抽数", "100"), checkbox("签到")))
        val period2 = period1(cumulativeOrder)
        val onlyCumulative = env.database.taskDao().findForPeriod(period2.id)
            .first { it.numericMode == NumericMode.CUMULATIVE }
        val signIn2 = env.database.taskDao().findForPeriod(period2.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        env.orderRepository.setOccurrenceCompleted(signIn2.id, true, "N:1")
        advanceTo(day2)
        retireAllExcept(cumulativeOrder, period2.id, listOf(signIn2))
        val cumulativeToggle = runCatching {
            env.orderRepository.setHistoricalRoundCompletion(cumulativeOrder, 1, onlyCumulative.id, true)
        }.exceptionOrNull()
        assertNotNull("cumulative tasks use their event history", cumulativeToggle)

        // A round that was never written has no history to correct and is not created.
        val missingRound = runCatching {
            env.orderRepository.setHistoricalRoundCompletion(orderId, 7, extra.id, true)
        }.exceptionOrNull()
        assertNotNull("no round row is created by a correction", missingRound)
    }

    /**
     * Ordinary (non-cumulative) numeric counterexample: a retired numeric task that was
     * never recorded. Historical completion must follow the normal path — the occurrence
     * is attributed to that round and stores a value that reaches the target, not null.
     */
    @Test
    fun retiredOrdinaryNumericTaskCompletesAtTargetWithoutPriorObservation() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), ordinaryNumeric("刷本", "100")))
        val period = period1(orderId)
        val signIn = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        val numeric = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.NUMERIC }
        val key = CountHosting.occurrenceKey(1)

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, key)
        assertNull("the numeric task keeps the round open", round(orderId, 1).completedAt)
        assertEquals(0, completedRounds(orderId))

        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))
        assertNull("no occurrence was ever written", env.database.occurrenceDao().findByKey(numeric.id, key))

        env.orderRepository.setHistoricalRoundCompletion(orderId, 1, numeric.id, true)

        val occurrence = checkNotNull(env.database.occurrenceDao().findByKey(numeric.id, key))
        assertEquals(
            "completion stores a target-reaching value, not null",
            0,
            BigDecimal("100").compareTo(occurrence.value),
        )
        assertEquals("attribution stays on the round's own date", day1, occurrence.gameDate)
        assertEquals("the occurrence belongs to the retired task", numeric.id, occurrence.taskDefinitionId)
        assertNotNull(occurrence.completedAt)
        assertNotNull("the historical round is complete again", round(orderId, 1).completedAt)
        assertEquals(1, completedRounds(orderId))
        assertEquals("no second occurrence key is created", 1, env.database.occurrenceDao().findForTask(numeric.id).size)

        val row = progress.countSessionHistory(orderId)
            .single { it.session.sessionIndex == 1 }
            .retiredDueTasks.single { it.taskId == numeric.id }
        assertTrue(row.completed)
        assertTrue(row.hasObservation)
        assertEquals(0, BigDecimal("100").compareTo(row.currentValue))
        assertEquals(day1, row.attributedDate)
    }

    /**
     * The confirmed counterexample: target 100 with a recorded 50 used to be marked complete
     * while the value stayed 50. Historical completion now raises the same occurrence to the
     * target, like the normal checkbox/numeric completion path.
     */
    @Test
    fun retiredOrdinaryNumericCompletionRaisesRecordedBelowTargetValue() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), ordinaryNumeric("刷本", "100")))
        val period = period1(orderId)
        val signIn = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        val numeric = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.NUMERIC }
        val key = CountHosting.occurrenceKey(1)

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, key)
        env.orderRepository.updateOccurrenceProgress(numeric.id, BigDecimal("50"), key)
        val before = checkNotNull(env.database.occurrenceDao().findByKey(numeric.id, key))
        assertEquals(0, BigDecimal("50").compareTo(before.value))
        assertNull("below target the row is not complete", before.completedAt)
        assertNull("the round still owes the numeric task", round(orderId, 1).completedAt)

        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))
        env.orderRepository.setHistoricalRoundCompletion(orderId, 1, numeric.id, true)

        val after = checkNotNull(env.database.occurrenceDao().findByKey(numeric.id, key))
        assertEquals("the same occurrence row is completed", before.id, after.id)
        assertEquals("a below-target value is raised to the target", 0, BigDecimal("100").compareTo(after.value))
        assertEquals("attribution never moves", day1, after.gameDate)
        assertNotNull(after.completedAt)
        assertEquals(1, completedRounds(orderId))
        assertEquals(1, env.database.occurrenceDao().findForTask(numeric.id).size)
    }

    /**
     * Above-target values are never lowered, and undo keeps the normal reset semantics
     * (value/completedAt/recordedAt cleared, row kept, round reopened). Re-completion after
     * undo stays consistent with the first completion.
     */
    @Test
    fun retiredOrdinaryNumericCompletionKeepsHigherValueAndUndoResetsLikeNormalPath() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), ordinaryNumeric("刷本", "100")))
        val period = period1(orderId)
        val signIn = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        val numeric = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.NUMERIC }
        val key = CountHosting.occurrenceKey(1)

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, key)
        env.orderRepository.updateOccurrenceProgress(numeric.id, BigDecimal("50"), key)
        val seeded = checkNotNull(env.database.occurrenceDao().findByKey(numeric.id, key))
        // A recorded value above the target must never be lowered by completion. Today's
        // numeric input completes as soon as it reaches the target, so the row is seeded
        // directly to pin the rule (the normal path's max(value, target)).
        env.database.occurrenceDao().updateValue(seeded.id, BigDecimal("150"))
        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))

        env.orderRepository.setHistoricalRoundCompletion(orderId, 1, numeric.id, true)
        val completed = checkNotNull(env.database.occurrenceDao().findByKey(numeric.id, key))
        assertEquals("an already higher value is preserved", 0, BigDecimal("150").compareTo(completed.value))
        assertNotNull(completed.completedAt)
        assertEquals(1, completedRounds(orderId))

        env.orderRepository.setHistoricalRoundCompletion(orderId, 1, numeric.id, false)
        val undone = checkNotNull(env.database.occurrenceDao().findByKey(numeric.id, key))
        assertEquals("undo keeps the occurrence row", completed.id, undone.id)
        assertNull("normal reset keeps no fabricated value", undone.value)
        assertNull(undone.completedAt)
        assertNull(undone.recordedAt)
        assertNull("the round reopens with the retired task outstanding", round(orderId, 1).completedAt)
        assertEquals(0, completedRounds(orderId))

        env.orderRepository.setHistoricalRoundCompletion(orderId, 1, numeric.id, true)
        val recompleted = checkNotNull(env.database.occurrenceDao().findByKey(numeric.id, key))
        assertEquals(undone.id, recompleted.id)
        assertEquals(0, BigDecimal("100").compareTo(recompleted.value))
        assertNotNull(recompleted.completedAt)
        assertNotNull(round(orderId, 1).completedAt)
        assertEquals(1, completedRounds(orderId))
        assertEquals(1, env.database.occurrenceDao().findForTask(numeric.id).size)
    }

    /**
     * B: every round of the order is resolved from one history read that loads the shared
     * task history once. Four written rounds carry the same (then retired) cumulative task:
     * distinct totals, one correction, one retraction and one round with no observation at
     * all. The read must stay read-only.
     */
    @Test
    fun retiredCumulativeHistoryResolvesEveryRoundInOneRead() = runBlocking {
        val orderId = createCountOrder(5, listOf(checkbox("签到"), cumulative("抽数", "100")))
        val period = period1(orderId)
        val signIn = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        val cumulativeTask = env.database.taskDao().findForPeriod(period.id)
            .first { it.numericMode == NumericMode.CUMULATIVE }

        // Round 1 (day 1): 100 -> complete.
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        val round1 = round(orderId, 1)
        val firstTotal = progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                scope = ProgressScope.Session(round1.id),
                attributedGameDate = day1,
                total = BigDecimal("100"),
            ),
        ).event
        assertEquals(1, completedRounds(orderId))

        // Round 2 (day 2): 120 -> complete.
        advanceTo(day2)
        val session2 = progress.ensureCountSession(orderId).session
        assertEquals(2, session2.sessionIndex)
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:2")
        val secondTotal = progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                scope = ProgressScope.Session(session2.id),
                attributedGameDate = day2,
                total = BigDecimal("120"),
            ),
        ).event
        assertEquals(2, completedRounds(orderId))

        // Round 3 (day 3): 150 -> complete.
        advanceTo(day3)
        val session3 = progress.ensureCountSession(orderId).session
        assertEquals(3, session3.sessionIndex)
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:3")
        progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                scope = ProgressScope.Session(session3.id),
                attributedGameDate = day3,
                total = BigDecimal("150"),
            ),
        )
        assertEquals(3, completedRounds(orderId))

        // Round 4 (day 4 = retirement day): only the checkbox is recorded; the cumulative
        // task was still due that day and has no observation.
        advanceTo(day4)
        retireAllExcept(orderId, period.id, listOf(signIn))
        val session4 = progress.ensureCountSession(orderId).session
        assertEquals(4, session4.sessionIndex)
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:4")
        assertNull("round 4 owes the retirement-day cumulative task", round(orderId, 4).completedAt)
        assertEquals(3, completedRounds(orderId))

        // History stays correctable after retirement: correct round 1 down, retract round 2.
        val correction = progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                targetEventId = firstTotal.id,
                total = BigDecimal("80"),
            ),
        ).event
        assertEquals(ProgressEventKind.CORRECT, correction.kind)
        val retraction = progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                targetEventId = secondTotal.id,
                total = null,
            ),
        ).event
        assertEquals(ProgressEventKind.RETRACT, retraction.kind)
        assertEquals(1, completedRounds(orderId))
        assertNull(round(orderId, 1).completedAt)
        assertNull(round(orderId, 2).completedAt)
        assertNotNull(round(orderId, 3).completedAt)
        assertNull(round(orderId, 4).completedAt)

        // Read-only: two reads change nothing and return the same history.
        val eventsBefore = env.database.taskProgressEventDao().findAll().size
        val occurrencesBefore = env.database.occurrenceDao().findAll().size
        val sessionsBefore = env.database.hostingSessionDao().countForOrder(orderId)
        val tasksBefore = env.database.taskDao().findForOrder(orderId).map { it.id to it.updatedAt }

        val history = progress.countSessionHistory(orderId)
        val again = progress.countSessionHistory(orderId)

        assertEquals("a read writes no event", eventsBefore, env.database.taskProgressEventDao().findAll().size)
        assertEquals("a read writes no occurrence", occurrencesBefore, env.database.occurrenceDao().findAll().size)
        assertEquals("a read creates no round", sessionsBefore, env.database.hostingSessionDao().countForOrder(orderId))
        assertEquals(
            "a read rewrites no task",
            tasksBefore,
            env.database.taskDao().findForOrder(orderId).map { it.id to it.updatedAt },
        )
        assertEquals("the history read is deterministic", history, again)

        assertEquals(listOf(1, 2, 3, 4), history.map { it.session.sessionIndex })
        assertEquals(listOf(day1, day2, day3, day4), history.map { it.attributedDate })
        history.forEach { entry ->
            assertTrue(
                "the retired task is history, not a current row",
                entry.taskRows.none { it.taskId == cumulativeTask.id },
            )
            assertTrue(entry.taskRows.any { it.taskId == signIn.id })
        }

        val rows = history.map { entry -> entry.retiredDueTasks.single { it.taskId == cumulativeTask.id } }
        assertEquals("completed rounds", listOf(false, false, true, false), rows.map { it.completed })
        // Round 2 keeps hasObservation: the retraction is still an event of that round's
        // scope. Round 4 was never observed at all.
        assertEquals("per-round observations", listOf(true, true, true, false), rows.map { it.hasObservation })
        val expectedValues = listOf("80", "0", "150", "0").map(::BigDecimal)
        rows.forEachIndexed { index, row ->
            assertEquals("round ${index + 1} current value", 0, expectedValues[index].compareTo(row.currentValue))
            assertEquals("round ${index + 1} attribution", day1.plusDays(index.toLong()), row.attributedDate)
            assertEquals(0, BigDecimal("100").compareTo(row.targetValue))
        }
    }

    /**
     * Retirement is not a licence to bypass the shared write guards: archived/cancelled
     * orders and a stale dataset identity are refused exactly like any other write.
     */
    @Test
    fun historicalCorrectionsRespectArchiveCancellationAndDatasetGuards() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), cumulative("抽数", "100")))
        val period = period1(orderId)
        val signIn = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        val cumulativeTask = env.database.taskDao().findForPeriod(period.id)
            .first { it.numericMode == NumericMode.CUMULATIVE }
        val round1 = round(orderId, 1)

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        val original = progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                scope = ProgressScope.Session(round1.id),
                attributedGameDate = day1,
                total = BigDecimal("50"),
            ),
        ).event
        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))

        // A scope the task does not belong to is refused before anything is written.
        val wrongScope = runCatching {
            progress.submitTotal(
                SubmitTotalCommand(
                    operationId = UUID.randomUUID().toString(),
                    taskId = cumulativeTask.id,
                    scope = ProgressScope.Period(period.id),
                    attributedGameDate = day1,
                    total = BigDecimal("100"),
                ),
            )
        }.exceptionOrNull()
        assertNotNull("a count order has no period-wide scope", wrongScope)

        // A stale dataset identity: the form is rejected as stale-command.
        val stale = runCatching {
            progress.correctTotal(
                CorrectTotalCommand(
                    operationId = UUID.randomUUID().toString(),
                    taskId = cumulativeTask.id,
                    targetEventId = original.id,
                    total = BigDecimal("100"),
                    expectedGeneration = DatasetGate.currentGeneration() - 1,
                ),
            )
        }.exceptionOrNull()
        assertNotNull("a stale correction is refused", stale)

        // Cancelling the order closes every remaining route.
        env.orderRepository.cancelOrder(orderId)
        val afterCancel = runCatching {
            progress.correctTotal(
                CorrectTotalCommand(
                    operationId = UUID.randomUUID().toString(),
                    taskId = cumulativeTask.id,
                    targetEventId = original.id,
                    total = BigDecimal("100"),
                ),
            )
        }.exceptionOrNull()
        assertNotNull("a cancelled order takes no more history writes", afterCancel)
        val afterCancelToggle = runCatching {
            env.orderRepository.setHistoricalRoundCompletion(orderId, 1, signIn.id, true)
        }.exceptionOrNull()
        assertNotNull("the round command is guarded too", afterCancelToggle)
    }
}
