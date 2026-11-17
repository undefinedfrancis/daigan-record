package com.daigan.record.feature.orders

import androidx.lifecycle.SavedStateHandle
import com.daigan.record.data.model.CountHosting
import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.HostingPeriod
import com.daigan.record.data.model.HostingSession
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.Order
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.Owner
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.repository.CountSessionHistoryEntry
import com.daigan.record.data.repository.ProgressCommandService
import com.daigan.record.data.repository.SubmitTotalCommand
import com.daigan.record.testing.FakeAttachmentRepository
import com.daigan.record.testing.FakeOrderRepository
import com.daigan.record.testing.FakeProgressCommandService
import com.daigan.record.testing.FakeReceiptRepository
import com.daigan.record.testing.FixedTimeProvider
import com.daigan.record.testing.MainDispatcherRule
import com.daigan.record.ui.navigation.Routes
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * R3-4: the actual panel workflow — fully completed orders keep read-only history,
 * selecting an earlier session changes only the viewed scope, and retry keys follow the
 * payload instead of being regenerated (or reused across different payloads).
 */
class CumulativePanelWorkflowTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val day = LocalDate.parse("2026-10-17")
    private val now: Instant = Instant.parse("2026-10-17T02:00:00Z")

    private fun cumulativeTask(
        id: String = "task-1",
        periodId: String? = "period-1",
        target: String = "10",
    ) = OrderTask(
        id = id,
        orderId = "order-1",
        periodId = periodId,
        title = "累计抽数",
        progressType = ProgressType.NUMERIC,
        startValue = BigDecimal.ZERO,
        targetValue = BigDecimal(target),
        currentValue = BigDecimal.ZERO,
        unit = "抽",
        recurrence = RecurrenceType.DAILY,
        weekdays = emptySet(),
        everyNDays = null,
        sortOrder = 0,
        completedAt = null,
        numericMode = NumericMode.CUMULATIVE,
    )

    private fun period(id: String = "period-1", index: Int = 0, target: Int = 2) = HostingPeriod(
        id = id,
        orderId = "order-1",
        periodIndex = index,
        startDate = day,
        endDate = day,
        priceCents = 1000,
        refresh = GameRefreshRulesDomain(
            zoneId = ZoneId.of("Asia/Shanghai"),
            dailyRefresh = LocalTime.of(4, 0),
            weeklyRefreshDay = DayOfWeek.MONDAY,
            weeklyRefreshTime = LocalTime.of(5, 0),
        ),
        createdAt = now,
        renewalOperationId = null,
        serviceCount = target,
    )

    private fun countDetail(
        periods: List<HostingPeriod>,
        tasks: List<OrderTask>,
        sessions: List<HostingSession>,
    ) = OrderDetail(
        order = Order(
            id = "order-1",
            accountId = "account-1",
            type = OrderType.HOSTING,
            title = "打卡托管",
            priceCents = null,
            paymentMode = PaymentMode.ONE_TIME,
            deadlineDate = null,
            notes = null,
            refresh = GameRefreshRulesDomain(
                zoneId = ZoneId.of("Asia/Shanghai"),
                dailyRefresh = LocalTime.of(4, 0),
                weeklyRefreshDay = DayOfWeek.MONDAY,
                weeklyRefreshTime = LocalTime.of(5, 0),
            ),
            createdAt = now,
            completedAt = now,
            hostingMode = HostingMode.COUNT_BASED,
            targetSessions = 2,
        ),
        owner = Owner("owner-1", 1, "老板", now, archived = false),
        account = GameAccount(
            id = "account-1",
            ownerId = "owner-1",
            gameName = "游戏",
            server = "官服",
            uid = "10001",
            loginAccount = "login",
            loginMethod = LoginMethod.VERIFICATION_CODE,
            password = null,
            createdAt = now,
            archived = false,
            archivedWithOwner = false,
        ),
        tasks = tasks,
        periods = periods,
        occurrences = emptyList(),
        attachments = emptyList(),
        receipts = emptyList(),
        receiptCorrections = emptyList(),
        today = day,
        now = now,
        sessions = sessions,
    )

    private fun shortDetail(task: OrderTask) = OrderDetail(
        order = Order(
            id = "order-1",
            accountId = "account-1",
            type = OrderType.SHORT_TASK,
            title = "短期累计",
            priceCents = 1000,
            paymentMode = PaymentMode.ONE_TIME,
            deadlineDate = null,
            notes = null,
            refresh = GameRefreshRulesDomain(
                zoneId = ZoneId.of("Asia/Shanghai"),
                dailyRefresh = LocalTime.of(4, 0),
                weeklyRefreshDay = DayOfWeek.MONDAY,
                weeklyRefreshTime = LocalTime.of(5, 0),
            ),
            createdAt = now,
            completedAt = null,
        ),
        owner = Owner("owner-1", 1, "老板", now, archived = false),
        account = GameAccount(
            id = "account-1",
            ownerId = "owner-1",
            gameName = "游戏",
            server = "官服",
            uid = "10001",
            loginAccount = "login",
            loginMethod = LoginMethod.VERIFICATION_CODE,
            password = null,
            createdAt = now,
            archived = false,
            archivedWithOwner = false,
        ),
        tasks = listOf(task.copy(periodId = null)),
        periods = emptyList(),
        occurrences = emptyList(),
        attachments = emptyList(),
        receipts = emptyList(),
        receiptCorrections = emptyList(),
        today = day,
        now = now,
    )

    private fun viewModel(
        orders: FakeOrderRepository,
        progress: FakeProgressCommandService,
    ) = OrderDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf(Routes.ORDER_ID_ARG to "order-1")),
        orderRepository = orders,
        attachmentRepository = FakeAttachmentRepository(),
        receiptRepository = FakeReceiptRepository(),
        timeProvider = FixedTimeProvider(),
        progressRepository = progress,
    )

    private fun completedSession(index: Int, periodId: String, id: String) = HostingSession(
        id = id,
        orderId = "order-1",
        sessionIndex = index,
        createdAt = now,
        completedAt = now,
        periodId = periodId,
    )

    @Test
    fun fullyCompletedOrderOpensItsLastSessionAsReadOnlyHistory() = runTest {
        val period = period()
        val task = cumulativeTask()
        val session1 = completedSession(1, period.id, "session-1")
        val session2 = completedSession(2, period.id, "session-2")
        val orders = FakeOrderRepository().apply {
            details.value = mapOf(
                "order-1" to countDetail(listOf(period), listOf(task), listOf(session1, session2)),
            )
        }
        val progress = FakeProgressCommandService().apply {
            activeState = CountHosting.ActiveCountState.AllPeriodsComplete
            historyEntries += CountSessionHistoryEntry(
                session = session1,
                periodId = period.id,
                periodIndex = 0,
                taskRows = emptyList(),
            )
            historyEntries += CountSessionHistoryEntry(
                session = session2,
                periodId = period.id,
                periodIndex = 0,
                taskRows = emptyList(),
            )
        }
        val vm = viewModel(orders, progress)

        vm.openCumulative(task.id)

        val panel = requireValue(vm.cumulative.value)
        assertTrue("history must be reachable after PERIOD_FULL", panel!!.historical)
        assertEquals(2, panel.sessionIndex)
        assertEquals(ProgressScope.Session(session2.id), panel.scope)
        assertEquals(0, progress.ensureCalls)
    }

    @Test
    fun selectingAnEarlierSessionChangesOnlyTheViewedScope() = runTest {
        val period = period()
        val task = cumulativeTask()
        val session1 = completedSession(1, period.id, "session-1")
        val session2 = completedSession(2, period.id, "session-2")
        val orders = FakeOrderRepository().apply {
            details.value = mapOf(
                "order-1" to countDetail(listOf(period), listOf(task), listOf(session1, session2)),
            )
        }
        val progress = FakeProgressCommandService().apply {
            activeState = CountHosting.ActiveCountState.AllPeriodsComplete
            historyEntries += CountSessionHistoryEntry(session1, period.id, 0, emptyList())
            historyEntries += CountSessionHistoryEntry(session2, period.id, 0, emptyList())
        }
        val vm = viewModel(orders, progress)
        vm.openCumulative(task.id)

        vm.selectCumulativeSession(session1.id)

        val panel = requireValue(vm.cumulative.value)
        assertEquals(ProgressScope.Session(session1.id), panel!!.scope)
        assertEquals(1, panel.sessionIndex)
        assertTrue(panel.historical)
        assertEquals("viewing history must never create a round", 0, progress.ensureCalls)
    }

    @Test
    fun explicitHistoryOpeningDoesNotEnsureASession() = runTest {
        val period = period()
        val task = cumulativeTask()
        val session1 = completedSession(1, period.id, "session-1")
        val orders = FakeOrderRepository().apply {
            details.value = mapOf(
                "order-1" to countDetail(listOf(period), listOf(task), listOf(session1)),
            )
        }
        val progress = FakeProgressCommandService().apply {
            activeState = CountHosting.ActiveCountState.AllPeriodsComplete
            historyEntries += CountSessionHistoryEntry(session1, period.id, 0, emptyList())
        }
        val vm = viewModel(orders, progress)

        vm.openCumulativeHistory(task.id, session1.id)

        assertEquals(0, progress.ensureCalls)
        requireValue(vm.cumulative.value)
        assertTrue(vm.cumulative.value!!.historical)
    }

    @Test
    fun samePayloadSubmitRetryReusesTheOperationIdAndEditedPayloadDoesNot() = runTest {
        val task = cumulativeTask(periodId = null)
        val orders = FakeOrderRepository().apply {
            details.value = mapOf("order-1" to shortDetail(task))
        }
        val progress = FakeProgressCommandService()
        val vm = viewModel(orders, progress)
        vm.openCumulative(task.id)
        assertFalse(vm.cumulative.value!!.historical)

        progress.failWith = IllegalStateException("网络中断")
        vm.submitCumulativeTotal("10")
        val firstKey = requireValue(vm.cumulative.value!!.pendingOperationId)

        progress.failWith = null
        vm.submitCumulativeTotal("10")
        assertEquals("identical retry must reuse the key", firstKey, lastSubmit(progress).operationId)

        progress.failWith = IllegalStateException("再次失败")
        vm.submitCumulativeTotal("12")
        val editedKey = requireValue(vm.cumulative.value!!.pendingOperationId)
        assertNotEquals("edited payload needs a new key", firstKey, editedKey)
        assertNotEquals(firstKey, editedKey)
    }

    @Test
    fun correctionRetryKeyFollowsTargetAndPayload() = runTest {
        val task = cumulativeTask(periodId = null)
        val orders = FakeOrderRepository().apply {
            details.value = mapOf("order-1" to shortDetail(task))
        }
        val progress = FakeProgressCommandService()
        val vm = viewModel(orders, progress)
        vm.openCumulative(task.id)
        vm.submitCumulativeTotal("4")
        val eventId = progress.events.single().id

        progress.failWith = IllegalStateException("结果未知")
        vm.correctCumulativeEvent(eventId, "6")
        val firstKey = requireValue(vm.cumulative.value!!.correctionAttempt?.operationId)

        progress.failWith = null
        vm.correctCumulativeEvent(eventId, "6")
        assertEquals(firstKey, lastCorrection(progress).operationId)

        progress.failWith = IllegalStateException("再次失败")
        vm.correctCumulativeEvent(eventId, "7")
        val editedKey = requireValue(vm.cumulative.value!!.correctionAttempt?.operationId)
        assertNotEquals(firstKey, editedKey)
    }

    private fun lastSubmit(fake: FakeProgressCommandService): SubmitTotalCommand =
        fake.submittedCommands.last()

    private fun lastCorrection(fake: FakeProgressCommandService) = fake.correctedCommands.last()

    private fun <T> requireValue(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
