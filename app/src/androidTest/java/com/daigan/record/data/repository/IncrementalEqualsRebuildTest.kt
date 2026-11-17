package com.daigan.record.data.repository

import androidx.test.core.app.ApplicationProvider
import com.daigan.record.backup.BackupDataCodec
import com.daigan.record.backup.BackupDatabaseAccess
import com.daigan.record.backup.DerivedStateReconstructor
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
import org.junit.Before
import org.junit.Test

/**
 * R3-2/R3-6 boundary: the state written incrementally by the repositories must equal a
 * from-scratch reconstruction over the same rows (including several count periods,
 * multiple rounds and a historical correction).
 */
class IncrementalEqualsRebuildTest {

    private lateinit var env: OrderTestEnvironment
    private lateinit var progress: CumulativeProgressRepository

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

    @Test
    fun incrementalCountStateEqualsFullReconstruction() = runBlocking {
        val day1 = LocalDate.parse("2026-10-17")
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
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
                    TaskDraft(title = "签到", recurrence = RecurrenceType.DAILY),
                    TaskDraft(
                        title = "抽数",
                        progressType = ProgressType.NUMERIC,
                        numericMode = NumericMode.CUMULATIVE,
                        startValue = "0",
                        targetValue = "10",
                        recurrence = RecurrenceType.DAILY,
                    ),
                ),
                hostingMode = HostingMode.COUNT_BASED,
                targetSessions = 2,
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
        val period1 = env.database.orderDao().findPeriods(orderId).single()
        val tasks = env.database.taskDao().findForPeriod(period1.id)
        val checkbox = tasks.first { it.progressType == ProgressType.CHECKBOX }
        val cumulative = tasks.first { it.numericMode == NumericMode.CUMULATIVE }
        val round1 = env.database.hostingSessionDao().findByIndex(orderId, 1)!!

        env.orderRepository.setOccurrenceCompleted(checkbox.id, true, "N:1")
        val firstEvent = progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulative.id,
                scope = ProgressScope.Session(round1.id),
                attributedGameDate = day1,
                total = BigDecimal("10"),
            ),
        )
        val round2 = progress.ensureCountSession(orderId).session
        env.orderRepository.setOccurrenceCompleted(checkbox.id, true, "N:2")
        progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulative.id,
                scope = ProgressScope.Session(round2.id),
                attributedGameDate = day1,
                total = BigDecimal("10"),
            ),
        )
        // Renewal adds a second period with its own target and roster.
        env.clock.set(Instant.parse("2026-10-18T02:00:00Z"))
        val renewal = env.orderRepository.renewCountHosting(
            orderId = orderId,
            operationId = UUID.randomUUID().toString(),
            targetSessions = 1,
            priceCents = 5000,
        )
        val renewalTasks = env.database.taskDao().findForPeriod(renewal.periodId)
        val renewalCheckbox = renewalTasks.first { it.progressType == ProgressType.CHECKBOX }
        val renewalCumulative = renewalTasks.first { it.numericMode == NumericMode.CUMULATIVE }
        val round3 = progress.ensureCountSession(orderId).session
        env.orderRepository.setOccurrenceCompleted(renewalCheckbox.id, true, "N:3")
        progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = renewalCumulative.id,
                scope = ProgressScope.Session(round3.id),
                attributedGameDate = LocalDate.parse("2026-10-18"),
                total = BigDecimal("10"),
            ),
        )
        // Historical correction reopens round 1 and must move the derived caches with it.
        progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulative.id,
                targetEventId = firstEvent.event.id,
                total = BigDecimal("5"),
            ),
        )

        val dump = BackupDatabaseAccess(
            ApplicationProvider.getApplicationContext(),
            env.database,
            env.clock,
        ).dump()
        val rebuilt = DerivedStateReconstructor.reconstruct(dump)

        assertEquals(0, rebuilt.report.sessionsCompleted)
        assertEquals(0, rebuilt.report.sessionsReopened)
        assertEquals(0, rebuilt.report.sessionTimestampsCorrected)
        assertEquals(0, rebuilt.report.taskCachesRefreshed)
        assertEquals(0, rebuilt.report.taskCompletionsChanged)
        assertEquals(0, rebuilt.report.orderFlagsChanged)
        assertEquals("", rebuilt.report.notes.joinToString("; "))
        assertEquals(
            "incremental state must equal the restore reconstruction",
            BackupDataCodec.encode(dump),
            BackupDataCodec.encode(rebuilt.dataSet),
        )
    }

    /**
     * Stage-3 repair F2: the historical due set includes a task retired **after** the round's
     * own attributed date. The incremental derivation and the restore reconstruction must
     * still agree after the retirement plus another write.
     */
    @Test
    fun incrementalCountStateEqualsRebuildAfterARetirement() = runBlocking {
        val day1 = LocalDate.parse("2026-10-17")
        val day2 = LocalDate.parse("2026-10-18")
        val (owner, account) = env.seedOwnerAndAccount()
        val orderId = env.orderRepository.createHostingOrder(
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
                    TaskDraft(title = "签到", recurrence = RecurrenceType.DAILY),
                    TaskDraft(title = "额外", recurrence = RecurrenceType.DAILY),
                    TaskDraft(
                        title = "抽数",
                        progressType = ProgressType.NUMERIC,
                        numericMode = NumericMode.CUMULATIVE,
                        startValue = "0",
                        targetValue = "10",
                        recurrence = RecurrenceType.DAILY,
                    ),
                ),
                hostingMode = HostingMode.COUNT_BASED,
                targetSessions = 3,
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
        val period = env.database.orderDao().findPeriods(orderId).single()
        val tasks = env.database.taskDao().findForPeriod(period.id)
        val signIn = tasks.first { it.title == "签到" }
        val extra = tasks.first { it.title == "额外" }
        val cumulative = tasks.first { it.numericMode == NumericMode.CUMULATIVE }
        val round1 = env.database.hostingSessionDao().findByIndex(orderId, 1)!!

        // Round 1 is validly complete on day 1.
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:1")
        env.orderRepository.setOccurrenceCompleted(extra.id, true, "N:1")
        progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulative.id,
                scope = ProgressScope.Session(round1.id),
                attributedGameDate = day1,
                total = BigDecimal("10"),
            ),
        )
        assertEquals(true, env.database.hostingSessionDao().findByIndex(orderId, 1)!!.completedAt != null)

        // Round 2 on day 2 is left unfinished, then its unfinished task is retired that day.
        env.clock.set(Instant.parse("2026-10-18T02:00:00Z"))
        val round2 = progress.ensureCountSession(orderId).session
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:2")
        progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulative.id,
                scope = ProgressScope.Session(round2.id),
                attributedGameDate = day2,
                total = BigDecimal("10"),
            ),
        )
        env.orderRepository.updateTaskDetails(
            orderId,
            listOf(signIn, cumulative).map { task ->
                TaskDraft(
                    id = task.id,
                    periodId = period.id,
                    title = task.title,
                    progressType = task.progressType,
                    recurrence = task.recurrence,
                    numericMode = task.numericMode,
                )
            },
        )
        assertEquals(true, env.database.taskDao().findById(extra.id)?.retiredAt != null)
        // Another write after the retirement: the shared service re-derives every round.
        env.orderRepository.setOccurrenceCompleted(signIn.id, true, "N:2")

        val dump = BackupDatabaseAccess(
            ApplicationProvider.getApplicationContext(),
            env.database,
            env.clock,
        ).dump()
        val rebuilt = DerivedStateReconstructor.reconstruct(dump)

        assertEquals(0, rebuilt.report.sessionsCompleted)
        assertEquals(0, rebuilt.report.sessionsReopened)
        assertEquals(0, rebuilt.report.taskCachesRefreshed)
        assertEquals(0, rebuilt.report.orderFlagsChanged)
        assertEquals("", rebuilt.report.notes.joinToString("; "))
        assertEquals(
            "the retirement-aware incremental state must equal the reconstruction",
            BackupDataCodec.encode(dump),
            BackupDataCodec.encode(rebuilt.dataSet),
        )
    }
}
