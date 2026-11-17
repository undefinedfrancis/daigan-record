package com.daigan.record.data.repository

import androidx.test.core.app.ApplicationProvider
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.shanghaiRules
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R3-3: one shared guard boundary for every new write — order state, ownership and the
 * dataset generation captured when the form opened.
 */
class CountWriteGuardTest {

    private lateinit var env: OrderTestEnvironment
    private lateinit var progress: CumulativeProgressRepository
    private val day = LocalDate.parse("2026-10-17")

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

    private suspend fun countOrder(): Triple<String, String, String> {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
            HostingOrderDraft(
                ownerId = owner.id,
                accountId = account.id,
                title = "打卡托管",
                startDate = day,
                endDate = day,
                priceYuan = "10.00",
                paymentMode = PaymentMode.ONE_TIME,
                refresh = shanghaiRules,
                tasks = listOf(
                    TaskDraft(
                        title = "累计",
                        progressType = ProgressType.NUMERIC,
                        numericMode = NumericMode.CUMULATIVE,
                        startValue = "0",
                        targetValue = "100",
                        recurrence = RecurrenceType.DAILY,
                    ),
                ),
                hostingMode = HostingMode.COUNT_BASED,
                targetSessions = 2,
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
        val period = env.database.orderDao().findPeriods(orderId).single()
        val task = env.database.taskDao().findForPeriod(period.id).single()
        return Triple(orderId, task.id, env.database.hostingSessionDao().findByIndex(orderId, 1)!!.id)
    }

    private fun submit(
        taskId: String,
        sessionId: String,
        total: String = "10",
        generation: Long? = null,
        operationId: String = UUID.randomUUID().toString(),
    ) = SubmitTotalCommand(
        operationId = operationId,
        taskId = taskId,
        scope = ProgressScope.Session(sessionId),
        attributedGameDate = day,
        total = BigDecimal(total),
        expectedGeneration = generation,
    )

    @Test
    fun cancelledOrderRejectsNewCumulativeWrites() = runBlocking {
        val (orderId, taskId, sessionId) = countOrder()
        env.orderRepository.cancelOrder(orderId)

        val submitError = runCatching { progress.submitTotal(submit(taskId, sessionId)) }.exceptionOrNull()
        val ensureError = runCatching { progress.ensureCountSession(orderId) }.exceptionOrNull()

        assertEquals("ORDER_NOT_WRITABLE", (submitError as ProgressCommandException).code)
        assertEquals("ORDER_NOT_WRITABLE", (ensureError as ProgressCommandException).code)
        assertTrue(progress.progressHistory(taskId).isEmpty())
    }

    @Test
    fun archivedOrderRejectsNewWrites() = runBlocking {
        val (orderId, taskId, sessionId) = countOrder()
        // Reach the agreed count, then archive through the product command.
        completeCountOrder(orderId, taskId, sessionId)
        env.orderRepository.archiveExpiredHostingOrder(orderId)

        val error = runCatching { progress.submitTotal(submit(taskId, sessionId)) }.exceptionOrNull()

        assertEquals("ORDER_NOT_WRITABLE", (error as ProgressCommandException).code)
    }

    /** Count orders may only be archived once the agreed rounds are done. */
    private suspend fun completeCountOrder(orderId: String, taskId: String, session1: String) {
        progress.submitTotal(submit(taskId, session1, "100"))
        val second = progress.ensureCountSession(orderId).session
        progress.submitTotal(submit(taskId, second.id, "100"))
    }

    @Test
    fun archivedOwnerRejectsWrites() = runBlocking {
        val (orderId, taskId, sessionId) = countOrder()
        completeCountOrder(orderId, taskId, sessionId)
        val order = env.database.orderDao().findById(orderId)!!
        val account = env.database.gameAccountDao().findById(order.accountId)!!
        env.ownerRepository.archiveOwner(account.ownerId)

        val error = runCatching { progress.submitTotal(submit(taskId, sessionId, "120")) }
            .exceptionOrNull()

        assertEquals("ORDER_NOT_WRITABLE", (error as ProgressCommandException).code)
    }

    @Test
    fun archivedAccountRejectsWrites() = runBlocking {
        val (orderId, taskId, sessionId) = countOrder()
        completeCountOrder(orderId, taskId, sessionId)
        val order = env.database.orderDao().findById(orderId)!!
        env.accountRepository.archiveAccount(order.accountId)

        val error = runCatching { progress.submitTotal(submit(taskId, sessionId, "120")) }
            .exceptionOrNull()

        assertEquals("ORDER_NOT_WRITABLE", (error as ProgressCommandException).code)
    }

    @Test
    fun completedOrderAllowsCorrectionButRejectsNewSubmission() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            ShortOrderDraft(
                ownerId = owner.id,
                accountId = account.id,
                title = "短期累计",
                priceYuan = "10.00",
                tasks = listOf(
                    TaskDraft(
                        title = "累计",
                        progressType = ProgressType.NUMERIC,
                        numericMode = NumericMode.CUMULATIVE,
                        startValue = "0",
                        targetValue = "100",
                        recurrence = RecurrenceType.ONE_TIME,
                    ),
                ),
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
        val task = env.database.taskDao().findForOrder(orderId).single()
        val applied = progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = task.id,
                scope = ProgressScope.Task,
                attributedGameDate = day,
                total = BigDecimal("100"),
            ),
        )
        assertEquals(true, env.database.taskDao().findById(task.id)?.completedAt != null)

        val newSubmission = runCatching {
            progress.submitTotal(
                SubmitTotalCommand(
                    operationId = UUID.randomUUID().toString(),
                    taskId = task.id,
                    scope = ProgressScope.Task,
                    attributedGameDate = day,
                    total = BigDecimal("120"),
                ),
            )
        }.exceptionOrNull()
        assertEquals("ORDER_NOT_WRITABLE", (newSubmission as ProgressCommandException).code)

        progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = task.id,
                targetEventId = applied.event.id,
                total = BigDecimal("90"),
            ),
        )
        val status = progress.cumulativeStatus(task.id, ProgressScope.Task)!!
        assertEquals(0, status.total.compareTo(BigDecimal("90")))
    }

    @Test
    fun aFormOpenedBeforeADatasetRestoreIsStaleEvenWhenIdsStillExist() = runBlocking {
        val (orderId, taskId, sessionId) = countOrder()
        val generation = DatasetGate.currentGeneration()

        // A restore in between bumps the dataset identity; the old form keeps its value.
        DatasetGate.datasetReplaced()

        val stale = runCatching {
            progress.submitTotal(submit(taskId, sessionId, generation = generation))
        }.exceptionOrNull()
        assertEquals("DATASET_CHANGED", (stale as ProgressCommandException).code)

        val ensureStale = runCatching {
            progress.ensureCountSession(orderId, expectedGeneration = generation)
        }.exceptionOrNull()
        assertEquals("DATASET_CHANGED", (ensureStale as ProgressCommandException).code)

        assertEquals(
            "stale attempts must not write",
            0,
            progress.progressHistory(taskId).size,
        )
        val fresh = progress.submitTotal(
            submit(taskId, sessionId, generation = DatasetGate.currentGeneration()),
        )
        assertTrue(fresh is ProgressCommandResult.Applied)
    }

    @Test
    fun successfulRetryWithinTheSameDatasetStaysIdempotent() = runBlocking {
        val (_, taskId, sessionId) = countOrder()
        val generation = DatasetGate.currentGeneration()
        val operationId = "guard-${UUID.randomUUID()}"

        val first = progress.submitTotal(
            submit(taskId, sessionId, generation = generation, operationId = operationId),
        )
        val second = progress.submitTotal(
            submit(taskId, sessionId, generation = generation, operationId = operationId),
        )

        assertTrue(first is ProgressCommandResult.Applied)
        assertTrue(second is ProgressCommandResult.IdempotentReplay)
        assertEquals(1, progress.progressHistory(taskId).size)
    }
}
