package com.daigan.record.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.testing.OrderTestEnvironment
import java.math.BigDecimal
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
import org.junit.runner.RunWith

/**
 * UF-038 against a real Room database.
 *
 * Covers the approved shared scenario IDs that need the event table and its
 * transaction: absolute totals stay absolute across days, retries are idempotent, a
 * different payload on the same operation id is rejected, corrections append instead of
 * rewriting, and retracting the last event reopens the target.
 */
@RunWith(AndroidJUnit4::class)
class CumulativeProgressRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment
    private lateinit var progress: CumulativeProgressRepository

    @Before
    fun setUp() {
        env = OrderTestEnvironment(context)
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

    /** 短期单 + 一个累计数值任务（目标 100，起始 0）。 */
    private suspend fun cumulativeShortTask(): String {
        val owner = env.ownerRepository.createOwner("小满")
        val account = env.accountRepository.createAccount(
            owner.id,
            com.daigan.record.data.model.AccountDraft(
                gameName = "鸣潮",
                server = "国服",
                uid = "1001",
                loginAccount = "demo",
            ),
        )
        val orderId = env.orderRepository.createShortOrder(
            ShortOrderDraft(
                ownerId = owner.id,
                accountId = account.id,
                title = "累计测试",
                priceYuan = "10.00",
                tasks = listOf(
                    TaskDraft(
                        title = "刷本",
                        progressType = ProgressType.NUMERIC,
                        numericMode = NumericMode.CUMULATIVE,
                        startValue = "0",
                        targetValue = "100",
                    ),
                ),
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
        return env.database.taskDao().findForOrder(orderId).first().id
    }

    private fun submit(
        taskId: String,
        total: String,
        date: LocalDate = LocalDate.parse("2026-10-17"),
        operationId: String = UUID.randomUUID().toString(),
    ) = SubmitTotalCommand(
        operationId = operationId,
        taskId = taskId,
        scope = ProgressScope.Task,
        attributedGameDate = date,
        total = BigDecimal(total),
    )

    @Test
    fun absoluteTotalStaysAbsoluteAcrossDays() = runBlocking {
        val taskId = cumulativeShortTask()

        progress.submitTotal(submit(taskId, "10", LocalDate.parse("2026-10-16")))
        progress.submitTotal(submit(taskId, "15", LocalDate.parse("2026-10-17")))

        val status = checkNotNull(progress.cumulativeStatus(taskId, ProgressScope.Task))
        // 第二天输入的是累计总值 15，而不是 10+15。
        assertEquals(0, status.total.compareTo(BigDecimal("15")))
        assertEquals(2, progress.progressHistory(taskId).count { it.totalValue != null })
    }

    @Test
    fun retryWithSameOperationIdWritesOnce() = runBlocking {
        val taskId = cumulativeShortTask()
        val operationId = "op-${UUID.randomUUID()}"

        val first = progress.submitTotal(submit(taskId, "10", operationId = operationId))
        val second = progress.submitTotal(submit(taskId, "10", operationId = operationId))

        assertTrue(first is ProgressCommandResult.Applied)
        assertTrue("同一 operationId 的重试必须回放而不是新增", second is ProgressCommandResult.IdempotentReplay)
        assertEquals(1, progress.progressHistory(taskId).size)
    }

    @Test
    fun differentPayloadOnSameOperationIdIsRejected() = runBlocking {
        val taskId = cumulativeShortTask()
        val operationId = "op-${UUID.randomUUID()}"
        progress.submitTotal(submit(taskId, "10", operationId = operationId))

        val error = runCatching {
            progress.submitTotal(submit(taskId, "20", operationId = operationId))
        }.exceptionOrNull()

        assertTrue(error is ProgressCommandException)
        assertEquals("OPERATION_CONFLICT", (error as ProgressCommandException).code)
        assertEquals(1, progress.progressHistory(taskId).size)
    }

    @Test
    fun correctionAppendsAndKeepsEarlierRows() = runBlocking {
        val taskId = cumulativeShortTask()
        val first = progress.submitTotal(submit(taskId, "10")) as ProgressCommandResult.Applied

        progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = taskId,
                targetEventId = first.event.id,
                total = BigDecimal("12"),
            ),
        )

        val events = progress.progressHistory(taskId)
        assertEquals("旧事件必须保留", 2, events.size)
        assertTrue(events.any { it.id == first.event.id })
        assertEquals(0, checkNotNull(progress.cumulativeStatus(taskId, ProgressScope.Task)).total.compareTo(BigDecimal("12")))
    }

    @Test
    fun retractingTheOnlyEventReopensTheTarget() = runBlocking {
        val taskId = cumulativeShortTask()
        val applied = progress.submitTotal(submit(taskId, "100")) as ProgressCommandResult.Applied
        assertNotNull(env.database.taskDao().findById(taskId)?.completedAt)

        progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = taskId,
                targetEventId = applied.event.id,
                total = null,
            ),
        )

        val status = checkNotNull(progress.cumulativeStatus(taskId, ProgressScope.Task))
        assertEquals(0, status.total.compareTo(BigDecimal.ZERO))
        assertNull("撤销后达标状态与完成时刻都要回落", env.database.taskDao().findById(taskId)?.completedAt)
    }

    @Test
    fun futureGameDateIsRejected() = runBlocking {
        val taskId = cumulativeShortTask()
        // 环境时钟固定在 2026-10-17；2026-10-18 属于未来游戏日。
        val error = runCatching {
            progress.submitTotal(submit(taskId, "10", LocalDate.parse("2026-10-18")))
        }.exceptionOrNull()
        assertEquals("FUTURE_GAME_DATE", (error as ProgressCommandException).code)
        assertTrue(progress.progressHistory(taskId).isEmpty())
    }

    @Test
    fun valueScaleAndRangeAreRejected() = runBlocking {
        val taskId = cumulativeShortTask()
        val tooPrecise = runCatching {
            progress.submitTotal(submit(taskId, "1.2345"))
        }.exceptionOrNull()
        assertEquals("VALUE_SCALE", (tooPrecise as ProgressCommandException).code)

        val negative = runCatching {
            progress.submitTotal(submit(taskId, "-1"))
        }.exceptionOrNull()
        assertEquals("VALUE_OUT_OF_RANGE", (negative as ProgressCommandException).code)
    }
}
