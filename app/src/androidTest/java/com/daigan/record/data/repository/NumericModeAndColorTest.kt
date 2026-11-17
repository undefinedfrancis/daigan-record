package com.daigan.record.data.repository

import androidx.test.core.app.ApplicationProvider
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.testing.OrderTestEnvironment
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R3-5: numeric-mode switching is enforced by the repository (not only the UI), and a
 * task colour override survives an ordinary edit while staying distinct from the order
 * colour.
 */
class NumericModeAndColorTest {

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

    private suspend fun shortTask(
        numericMode: NumericMode? = NumericMode.CUMULATIVE,
        colorArgb: Int? = null,
    ): Pair<String, String> {
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createShortOrder(
            ShortOrderDraft(
                ownerId = owner.id,
                accountId = account.id,
                title = "数值任务",
                priceYuan = "10.00",
                tasks = listOf(
                    TaskDraft(
                        title = "累计",
                        progressType = ProgressType.NUMERIC,
                        numericMode = numericMode,
                        startValue = "0",
                        targetValue = "100",
                        recurrence = RecurrenceType.ONE_TIME,
                        colorArgb = colorArgb,
                    ),
                ),
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
        return orderId to env.database.taskDao().findForOrder(orderId).single().id
    }

    @Test
    fun numericModeSwitchIsBlockedOnceAnEventExistsEvenAtStartValue() = runBlocking {
        val (_, taskId) = shortTask()
        // An explicit observation whose total still equals startValue: no occurrence,
        // no completion, currentValue == startValue — but history exists.
        progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = taskId,
                scope = ProgressScope.Task,
                attributedGameDate = day,
                total = BigDecimal.ZERO,
            ),
        )
        assertEquals(1, progress.progressHistory(taskId).size)

        val direct = runCatching {
            env.orderRepository.updateTaskNumericMode(taskId, NumericMode.PER_OCCURRENCE)
        }.exceptionOrNull()
        assertTrue(direct is IllegalArgumentException)

        val stored = env.database.taskDao().findById(taskId)!!
        val draft = TaskDraft(
            id = stored.id,
            title = stored.title,
            progressType = ProgressType.NUMERIC,
            startValue = "0",
            targetValue = "100",
            recurrence = RecurrenceType.ONE_TIME,
            numericMode = NumericMode.PER_OCCURRENCE,
        )
        val throughEdit = runCatching {
            env.orderRepository.updateTaskDetails(stored.orderId, listOf(draft))
        }.exceptionOrNull()
        assertTrue(throughEdit is IllegalStateException)
        assertEquals(
            NumericMode.CUMULATIVE,
            env.database.taskDao().findById(taskId)!!.numericMode,
        )
    }

    @Test
    fun numericModeSwitchWithoutHistoryIsAllowed() = runBlocking {
        val (_, taskId) = shortTask()

        env.orderRepository.updateTaskNumericMode(taskId, NumericMode.PER_OCCURRENCE)

        assertEquals(NumericMode.PER_OCCURRENCE, env.database.taskDao().findById(taskId)!!.numericMode)
    }

    @Test
    fun taskColourIsNotDiscardedByAnOrdinaryEditAndStaysDistinctFromTheOrder() = runBlocking {
        val (orderId, taskId) = shortTask(colorArgb = 0xFF112233.toInt())
        env.orderRepository.setOrderColor(orderId, 0xFF445566.toInt())

        val stored = env.database.taskDao().findById(taskId)!!
        // The editor sends no colour when the user only edits the title.
        env.orderRepository.updateTaskDetails(
            orderId,
            listOf(
                TaskDraft(
                    id = stored.id,
                    title = "改名后的累计",
                    progressType = ProgressType.NUMERIC,
                    startValue = "0",
                    targetValue = "100",
                    recurrence = RecurrenceType.ONE_TIME,
                    numericMode = NumericMode.CUMULATIVE,
                ),
            ),
        )

        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals("改名后的累计", detail.tasks.single().title)
        assertEquals(0xFF112233.toInt(), detail.tasks.single().colorArgb)
        assertEquals(0xFF445566.toInt(), detail.order.colorArgb)

        // An explicit task colour is persisted and a later edit keeps it.
        env.orderRepository.updateTaskDetails(
            orderId,
            listOf(
                TaskDraft(
                    id = stored.id,
                    title = "改名后的累计",
                    progressType = ProgressType.NUMERIC,
                    startValue = "0",
                    targetValue = "100",
                    recurrence = RecurrenceType.ONE_TIME,
                    numericMode = NumericMode.CUMULATIVE,
                    colorArgb = 0xFF00FF00.toInt(),
                ),
            ),
        )
        assertEquals(
            0xFF00FF00.toInt(),
            env.orderRepository.observeOrderDetail(orderId).first()!!.tasks.single().colorArgb,
        )
    }

    @Test
    fun taskWithoutColourInheritsRatherThanCopiesTheOrderColour() = runBlocking {
        val (orderId, taskId) = shortTask(colorArgb = null)
        env.orderRepository.setOrderColor(orderId, 0xFFAA0000.toInt())

        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!

        assertNull("task override stays absent", detail.tasks.single().colorArgb)
        assertEquals(0xFFAA0000.toInt(), detail.order.colorArgb)
        assertTrue(detail.tasks.single().periodId == null)
    }
}
