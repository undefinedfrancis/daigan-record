package com.daigan.record.feature.orders

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.daigan.record.data.local.entity.TaskDefinitionEntity
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
import com.daigan.record.data.repository.CumulativeProgressRepository
import com.daigan.record.data.repository.SubmitTotalCommand
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.shanghaiRules
import com.daigan.record.ui.navigation.Routes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * H1 reachability: a retired task's recovery is driven through the production
 * ViewModel route ([OrderDetailViewModel.openCumulativeHistory] →
 * [OrderDetailViewModel.submitCumulativeTotal] and
 * [OrderDetailViewModel.setHistoricalRoundCompletion]), not only through the repository.
 *
 * Repository-only success does not prove that a user can actually reach the correction,
 * so this test walks the same entry points the order detail page exposes.
 */
class RetiredTaskHistoryRouteTest {

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

    /** The recovered path: open the removed task's history and correct its observation. */
    @Test
    fun retiredCumulativeTaskIsRecoverableThroughTheHistoryPanel() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), cumulative("抽数", "100")))
        val period = env.database.orderDao().findPeriods(orderId).single()
        val signIn = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        val cumulativeTask = env.database.taskDao().findForPeriod(period.id)
            .first { it.numericMode == NumericMode.CUMULATIVE }
        val round1 = checkNotNull(env.database.hostingSessionDao().findByIndex(orderId, 1))

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
        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))

        val vm = viewModel(orderId)
        val loaded = await(vm.uiState) { it.detail != null && !it.loading }
        val detail = requireNotNull(loaded.detail)

        // Requirement: the removed task stays out of the current action list.
        assertEquals(listOf(signIn.id), detail.tasksForPeriod(period.id).map { it.id })
        assertEquals(0, completedRounds(orderId))

        // ...and it is named, with its own historical round, in the historical context.
        val retired = loaded.retiredTasks.single { it.taskId == cumulativeTask.id }
        assertTrue(retired.cumulative)
        assertEquals(1, retired.rounds.size)
        val roundEntry = retired.rounds.single()
        assertEquals(1, roundEntry.sessionIndex)
        assertEquals(day1, roundEntry.attributedDate)
        assertTrue("the observation exists", roundEntry.hasObservation)
        assertFalse("the pre-correction state is incomplete", roundEntry.completed)

        // The historical panel opens for that very round.
        vm.openCumulativeHistory(cumulativeTask.id, roundEntry.sessionId)
        val panel = requireNotNull(await(vm.cumulative) { it != null })
        assertTrue(panel.historical)
        assertEquals("the correction is dated at the round, not at today", day1, panel.historicalSubmitDate)
        assertEquals(0, BigDecimal("50").compareTo(panel.currentTotal))

        // Recovery through the route a user has.
        vm.submitCumulativeTotal("100")
        val recovered = requireNotNull(
            await(vm.cumulative) {
                it != null && it.error == null && !it.submitting &&
                    it.currentTotal.compareTo(BigDecimal("100")) == 0
            },
        )
        assertEquals(0, BigDecimal("100").compareTo(recovered.currentTotal))

        val after = await(vm.uiState) { state ->
            state.retiredTasks.singleOrNull { it.taskId == cumulativeTask.id }
                ?.rounds?.singleOrNull()?.completed == true
        }
        assertTrue(after.retiredTasks.single { it.taskId == cumulativeTask.id }.rounds.single().completed)
        assertNotNull("the historical round is complete again", round(orderId, 1)?.completedAt)
        assertEquals(1, completedRounds(orderId))
        // Append-only: the original observation survives the correction.
        val history = progress.progressHistory(cumulativeTask.id)
        assertEquals(2, history.size)
        assertEquals(0, BigDecimal("50").compareTo(history.first().totalValue))
        assertEquals(0, BigDecimal("100").compareTo(history.last().totalValue))
    }

    /** The checkbox twin: the round of a removed task is corrected from its history card. */
    @Test
    fun retiredCheckboxTaskIsRecoverableThroughTheHistoryCard() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), checkbox("额外任务")))
        val period = env.database.orderDao().findPeriods(orderId).single()
        val signIn = env.database.taskDao().findForPeriod(period.id).first { it.title == "签到" }
        val extra = env.database.taskDao().findForPeriod(period.id).first { it.title == "额外任务" }

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))

        val vm = viewModel(orderId)
        val loaded = await(vm.uiState) { it.detail != null && !it.loading }
        val roundEntry = loaded.retiredTasks.single { it.taskId == extra.id }.rounds.single()
        assertEquals(1, roundEntry.sessionIndex)
        assertFalse(roundEntry.hasObservation)
        assertFalse(roundEntry.completed)

        vm.setHistoricalRoundCompletion(extra.id, 1, true)
        val completed = await(vm.uiState) { state ->
            state.retiredTasks.singleOrNull { it.taskId == extra.id }
                ?.rounds?.singleOrNull()?.completed == true
        }
        assertNotNull(completed.detail)
        assertTrue(completed.retiredTasks.single { it.taskId == extra.id }.rounds.single().completed)
        assertNotNull(round(orderId, 1)?.completedAt)
        assertEquals(1, completedRounds(orderId))

        // The same card retracts the record again; nothing is deleted.
        vm.setHistoricalRoundCompletion(extra.id, 1, false)
        val reopened = await(vm.uiState) { state ->
            state.retiredTasks.singleOrNull { it.taskId == extra.id }
                ?.rounds?.singleOrNull()?.completed == false
        }
        assertFalse(reopened.retiredTasks.single { it.taskId == extra.id }.rounds.single().completed)
        assertNull(round(orderId, 1)?.completedAt)
        assertEquals(0, completedRounds(orderId))
    }

    /**
     * A removed *ordinary numeric* task (not cumulative) reaches the same repaired command
     * from the history card: the persisted occurrence is raised to the target instead of
     * staying at its below-target value.
     */
    @Test
    fun retiredOrdinaryNumericTaskIsRecoverableThroughTheHistoryCard() = runBlocking {
        val orderId = createCountOrder(2, listOf(checkbox("签到"), ordinaryNumeric("刷本", "100")))
        val period = env.database.orderDao().findPeriods(orderId).single()
        val signIn = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        val numeric = env.database.taskDao().findForPeriod(period.id)
            .first { it.progressType == ProgressType.NUMERIC }
        val key = CountHosting.occurrenceKey(1)

        env.orderRepository.setOccurrenceCompleted(signIn.id, true, key)
        env.orderRepository.updateOccurrenceProgress(numeric.id, BigDecimal("50"), key)
        advanceTo(day2)
        retireAllExcept(orderId, period.id, listOf(signIn))

        val vm = viewModel(orderId)
        val loaded = await(vm.uiState) { it.detail != null && !it.loading }
        val retired = loaded.retiredTasks.single { it.taskId == numeric.id }
        assertFalse("an ordinary numeric task is not the cumulative panel", retired.cumulative)
        val roundEntry = retired.rounds.single()
        assertEquals(1, roundEntry.sessionIndex)
        assertFalse("the pre-correction state is incomplete", roundEntry.completed)
        // Shipped definition: a non-cumulative row is "observed" only once it is completed,
        // so a recorded below-target value is still not an observation.
        assertFalse(roundEntry.hasObservation)

        vm.setHistoricalRoundCompletion(numeric.id, roundEntry.sessionIndex, true)

        val completed = await(vm.uiState) { state ->
            state.retiredTasks.singleOrNull { it.taskId == numeric.id }
                ?.rounds?.singleOrNull()?.completed == true
        }
        assertTrue(completed.retiredTasks.single { it.taskId == numeric.id }.rounds.single().completed)
        assertEquals(1, completedRounds(orderId))
        assertNotNull(round(orderId, 1)?.completedAt)

        val occurrence = checkNotNull(env.database.occurrenceDao().findByKey(numeric.id, key))
        assertEquals(
            "the repaired command raises the recorded value to the target",
            0,
            BigDecimal("100").compareTo(occurrence.value),
        )
        assertEquals("attribution stays on the round's own date", day1, occurrence.gameDate)
    }

    private fun viewModel(orderId: String) = OrderDetailViewModel(
        SavedStateHandle(mapOf(Routes.ORDER_ID_ARG to orderId)),
        env.orderRepository,
        env.attachmentRepository,
        env.receiptRepository,
        env.clock,
        progress,
    )

    private suspend fun <T> await(flow: Flow<T>, timeoutMs: Long = 15_000, ready: (T) -> Boolean): T =
        withTimeout(timeoutMs) { flow.first(ready) }

    private suspend fun completedRounds(orderId: String): Int =
        CountHosting.completedCount(
            env.database.hostingSessionDao().findForOrder(orderId).map { it.toModel() },
        )

    private suspend fun round(orderId: String, index: Int) =
        env.database.hostingSessionDao().findByIndex(orderId, index)

    private fun advanceTo(date: LocalDate) {
        env.clock.set(Instant.parse("${date}T02:00:00Z"))
    }

    private fun checkbox(title: String) = TaskDraft(
        title = title,
        progressType = ProgressType.CHECKBOX,
        recurrence = RecurrenceType.DAILY,
    )

    private fun cumulative(title: String, target: String) = TaskDraft(
        title = title,
        progressType = ProgressType.NUMERIC,
        numericMode = NumericMode.CUMULATIVE,
        startValue = "0",
        targetValue = target,
        recurrence = RecurrenceType.DAILY,
    )

    /** 普通数值任务（非累计）：每轮各自记录进度。 */
    private fun ordinaryNumeric(title: String, target: String) = TaskDraft(
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

    /** Retires every task of [periodId] that is not [kept], through the product command. */
    private suspend fun retireAllExcept(
        orderId: String,
        periodId: String,
        kept: List<TaskDefinitionEntity>,
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
    }
}
