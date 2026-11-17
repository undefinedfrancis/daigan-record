package com.daigan.record.data.repository

import androidx.test.core.app.ApplicationProvider
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
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage-3 repair F4: `ensureCountSession` returns an **existing** round before any
 * completed/full rejection, so an already successful ensure can be replayed after the work
 * (or the whole order) finished. Safety guards still come first: dataset generation, order
 * existence/type, cancellation/archive and account/owner ownership.
 */
class CountSessionReplayTest {

    private lateinit var env: OrderTestEnvironment
    private lateinit var progress: CumulativeProgressRepository

    private val day1 = LocalDate.parse("2026-10-17")
    private val day2 = LocalDate.parse("2026-10-18")

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
                tasks = listOf(
                    TaskDraft(
                        title = "累计抽数",
                        progressType = ProgressType.NUMERIC,
                        numericMode = NumericMode.CUMULATIVE,
                        startValue = "0",
                        targetValue = "100",
                        recurrence = RecurrenceType.DAILY,
                    ),
                ),
                hostingMode = HostingMode.COUNT_BASED,
                targetSessions = target,
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
    }

    private suspend fun taskOf(orderId: String) =
        env.database.taskDao().findForOrder(orderId).single { it.retiredAt == null }

    private suspend fun finishRound(orderId: String, sessionIndex: Int, total: String = "100") {
        val task = taskOf(orderId)
        val session = checkNotNull(env.database.hostingSessionDao().findByIndex(orderId, sessionIndex))
        progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = task.id,
                scope = ProgressScope.Session(session.id),
                attributedGameDate = day1,
                total = BigDecimal(total),
            ),
        )
    }

    /** Everything an ensure replay must leave untouched. */
    private suspend fun fingerprint(orderId: String): String {
        val order = env.database.orderDao().findById(orderId)!!
        val sessions = env.database.hostingSessionDao().findForOrder(orderId)
            .sortedBy { it.sessionIndex }
            .joinToString(";") { "${it.id}|${it.sessionIndex}|${it.periodId}|${it.createdAt}|${it.completedAt}|${it.completionBatchId}" }
        val tasks = env.database.taskDao().findForOrder(orderId)
            .sortedBy { it.sortOrder }
            .joinToString(";") { "${it.id}|${it.currentValue}|${it.completedAt}|${it.completionBatchId}|${it.updatedAt}" }
        val events = env.database.taskProgressEventDao().findAll()
            .sortedBy { it.id }
            .joinToString(";") { "${it.id}|${it.sequence}|${it.kind}|${it.totalValue}|${it.recordedAt}" }
        val occurrences = env.database.occurrenceDao().findForOrder(orderId)
            .sortedBy { it.id }
            .joinToString(";") { "${it.id}|${it.occurrenceKey}|${it.completedAt}|${it.value}" }
        return listOf(order.completedAt, order.updatedAt, sessions, tasks, events, occurrences).joinToString("#")
    }

    // ---------------------------------------------------------------- F4

    @Test
    fun ensureReplayAfterOrderCompletionReturnsTheSameSessionWithoutWrites() = runBlocking {
        val orderId = createCountOrder(target = 1)
        finishRound(orderId, 1)
        val first = checkNotNull(env.database.hostingSessionDao().findByIndex(orderId, 1))
        assertNotNull("the single agreed round completes", first.completedAt)
        assertNotNull("the order completes", env.database.orderDao().findById(orderId)?.completedAt)
        val before = fingerprint(orderId)

        val replay = progress.ensureCountSession(orderId, 1)

        assertEquals(first.id, replay.session.id)
        assertEquals(1, replay.session.sessionIndex)
        assertFalse("nothing is created", replay.created)
        assertEquals("no event, timestamp, cache or allocation changed", before, fingerprint(orderId))
        assertEquals(1, env.database.hostingSessionDao().countForOrder(orderId))
    }

    @Test
    fun nonexistentSessionAfterCompletionIsStillRejected() = runBlocking {
        val orderId = createCountOrder(target = 1)
        finishRound(orderId, 1)
        val before = fingerprint(orderId)

        val error = runCatching { progress.ensureCountSession(orderId, 2) }.exceptionOrNull()

        assertEquals("ORDER_NOT_WRITABLE", (error as ProgressCommandException).code)
        assertEquals(before, fingerprint(orderId))
        assertNull(env.database.hostingSessionDao().findByIndex(orderId, 2))
    }

    @Test
    fun existingSessionRequestAfterCancellationIsRejected() = runBlocking {
        val orderId = createCountOrder(target = 1)
        finishRound(orderId, 1)
        env.orderRepository.cancelOrder(orderId)

        val error = runCatching { progress.ensureCountSession(orderId, 1) }.exceptionOrNull()

        assertEquals("ORDER_NOT_WRITABLE", (error as ProgressCommandException).code)
    }

    @Test
    fun existingSessionRequestAfterArchiveIsRejected() = runBlocking {
        val orderId = createCountOrder(target = 1)
        finishRound(orderId, 1)
        env.orderRepository.archiveExpiredHostingOrder(orderId)

        val error = runCatching { progress.ensureCountSession(orderId, 1) }.exceptionOrNull()

        assertEquals("ORDER_NOT_WRITABLE", (error as ProgressCommandException).code)
    }

    @Test
    fun existingSessionRequestWithAStaleGenerationIsRejected() = runBlocking {
        val orderId = createCountOrder(target = 2)
        val generation = DatasetGate.currentGeneration()
        val session = progress.ensureCountSession(orderId, 1, generation).session
        assertEquals(1, session.sessionIndex)

        // A completed restore replaces the dataset identity.
        DatasetGate.datasetReplaced()

        val error = runCatching { progress.ensureCountSession(orderId, 1, generation) }.exceptionOrNull()
        assertEquals("DATASET_CHANGED", (error as ProgressCommandException).code)

        val fresh = progress.ensureCountSession(orderId, 1, DatasetGate.currentGeneration())
        assertEquals(session.id, fresh.session.id)
        assertFalse(fresh.created)
    }

    @Test
    fun existingSessionInAFullPeriodIsReturnedWhileAnotherPeriodIsActive() = runBlocking {
        val orderId = createCountOrder(target = 1)
        finishRound(orderId, 1)
        val first = checkNotNull(env.database.hostingSessionDao().findByIndex(orderId, 1))
        env.clock.set(java.time.Instant.parse("2026-10-18T02:00:00Z"))
        val renewal = env.orderRepository.renewCountHosting(
            orderId = orderId,
            operationId = "renew-replay-${UUID.randomUUID()}",
            targetSessions = 2,
            priceCents = 1000,
        )
        assertNull("the renewal reopens the order", env.database.orderDao().findById(orderId)?.completedAt)
        assertEquals(day2, env.database.orderDao().findPeriod(renewal.periodId)?.startDate)

        val replay = progress.ensureCountSession(orderId, 1)

        assertEquals("the original round is returned, not a new one", first.id, replay.session.id)
        assertFalse(replay.created)
        assertEquals(1, env.database.hostingSessionDao().countForOrder(orderId))

        // The new period is still the one new work is allocated to.
        val active = progress.ensureCountSession(orderId).session
        assertEquals(renewal.periodId, active.periodId)
        assertEquals(2, active.sessionIndex)
        assertTrue(active.sessionIndex > first.sessionIndex)
    }

    @Test
    fun aRoundOfAnotherOrderIsNeverReturned() = runBlocking {
        val orderA = createCountOrder(target = 2)
        val orderB = createCountOrder(target = 2)
        finishRound(orderB, 1)
        val otherOrderIndex = progress.ensureCountSession(orderB, 2).session
        assertEquals(2, otherOrderIndex.sessionIndex)
        assertNotNull(env.database.hostingSessionDao().findByIndex(orderB, 2))

        // Order A's allocated index is still 1; index 2 belongs to another order's scope.
        val error = runCatching { progress.ensureCountSession(orderA, 2) }.exceptionOrNull()

        assertEquals("SCOPE_OWNERSHIP_INVALID", (error as ProgressCommandException).code)
        assertNull(env.database.hostingSessionDao().findByIndex(orderA, 2))
    }
}
