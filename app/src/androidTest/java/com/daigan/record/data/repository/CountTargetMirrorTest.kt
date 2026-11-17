package com.daigan.record.data.repository

import androidx.test.core.app.ApplicationProvider
import com.daigan.record.backup.BackupDatabaseAccess
import com.daigan.record.backup.BackupDataSet
import com.daigan.record.backup.BackupDataCodec
import com.daigan.record.backup.BackupNormalizer
import com.daigan.record.backup.DerivedStateReconstructor
import com.daigan.record.data.local.toModel
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.OrderEditDraft
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Stage-3 repair F3: `orders.targetSessions` mirrors the **highest-periodIndex count
 * period's** `serviceCount`. It is a summary mirror for single-period tools and the backup
 * shape; it never governs another period's completion.
 *
 * Covered here (real Room + real repositories): creation, renewal, latest-period target
 * changes, older-period target changes (repository command and the unified order edit),
 * and the exported/restored meaning.
 */
class CountTargetMirrorTest {

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
                    TaskDraft(title = "签到", recurrence = RecurrenceType.DAILY),
                    TaskDraft(
                        title = "抽数",
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

    private suspend fun mirror(orderId: String): Int? =
        env.database.orderDao().findById(orderId)?.targetSessions

    private suspend fun periodTargets(orderId: String): List<Int?> =
        env.database.orderDao().findPeriods(orderId)
            .sortedBy { it.periodIndex }
            .map { it.serviceCount }

    private suspend fun renew(orderId: String, target: Int) = env.orderRepository.renewCountHosting(
        orderId = orderId,
        operationId = "renew-${UUID.randomUUID()}",
        targetSessions = target,
        priceCents = 1000,
    )

    // ---------------------------------------------------------------- F3

    @Test
    fun creationInitialisesTheMirror() = runBlocking {
        val orderId = createCountOrder(target = 2)

        assertEquals(2, mirror(orderId))
        assertEquals(listOf<Int?>(2), periodTargets(orderId))
    }

    @Test
    fun renewalMovesTheMirrorToTheNewLatestPeriod() = runBlocking {
        val orderId = createCountOrder(target = 2)
        val renewal = renew(orderId, 1)

        assertEquals("period-local targets are untouched", listOf<Int?>(2, 1), periodTargets(orderId))
        assertEquals("the mirror follows the highest periodIndex", 1, mirror(orderId))
        assertEquals(1, env.database.orderDao().findPeriod(renewal.periodId)?.serviceCount)
    }

    @Test
    fun changingTheLatestPeriodTargetUpdatesTheMirror() = runBlocking {
        val orderId = createCountOrder(target = 2)
        val renewal = renew(orderId, 1)

        progress.updateCountTarget(orderId, renewal.periodId, 3)

        assertEquals(listOf<Int?>(2, 3), periodTargets(orderId))
        assertEquals(3, mirror(orderId))
    }

    @Test
    fun changingAnOlderPeriodTargetLeavesTheLatestMirrorAlone() = runBlocking {
        val orderId = createCountOrder(target = 2)
        val older = env.database.orderDao().findPeriods(orderId).single { it.periodIndex == 0 }
        renew(orderId, 1)

        progress.updateCountTarget(orderId, older.id, 5)

        assertEquals(listOf<Int?>(5, 1), periodTargets(orderId))
        assertEquals("the mirror still describes the latest period", 1, mirror(orderId))
    }

    @Test
    fun orderEditOfAnOlderPeriodTargetLeavesTheLatestMirrorAlone() = runBlocking {
        val orderId = createCountOrder(target = 2)
        renew(orderId, 1)
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        val older = detail.periods.single { it.periodIndex == 0 }

        // The unified editor prefills the first count period, exactly the older one here.
        val draft = OrderEditDraft.of(detail).copy(
            periodId = older.id,
            targetSessions = 4,
            operationId = "edit-${UUID.randomUUID()}",
        )
        env.orderRepository.applyOrderEdit(draft)

        assertEquals(listOf<Int?>(4, 1), periodTargets(orderId))
        assertEquals(1, mirror(orderId))
    }

    /** The exported row and the restored meaning both describe the latest period. */
    @Test
    fun exportedAndRestoredStateKeepsTheLatestPeriodMirror() = runBlocking {
        val orderId = createCountOrder(target = 2)
        renew(orderId, 1)
        val periods = env.database.orderDao().findPeriods(orderId).sortedBy { it.periodIndex }

        val dump = BackupDatabaseAccess(
            ApplicationProvider.getApplicationContext(),
            env.database,
            env.clock,
        ).dump()

        assertEquals("1", rowValue(dump, "orders", orderId, "targetSessions"))
        assertEquals("2", rowValue(dump, "hosting_periods", periods[0].id, "serviceCount"))
        assertEquals("1", rowValue(dump, "hosting_periods", periods[1].id, "serviceCount"))

        // A contemporary file normalizes without inventing anything...
        val normalized = BackupNormalizer.normalize(dump, 15)
        assertEquals("1", rowValue(normalized.dataSet, "orders", orderId, "targetSessions"))

        // ...and the reconstruction finds the derived mirror already consistent.
        val rebuilt = DerivedStateReconstructor.reconstruct(normalized.dataSet)
        assertEquals(0, rebuilt.report.orderFlagsChanged)
        assertEquals(BackupDataCodec.encode(dump), BackupDataCodec.encode(rebuilt.dataSet))

        // Period-local targets still govern completion: the two periods stay distinct.
        assertNotNull(periods[0].serviceCount)
        assertNotNull(periods[1].serviceCount)
    }

    /** A stale mirror left by an older build is repaired by the next count write. */
    @Test
    fun aCountWriteRepairsAMirrorLeftStaleByAnOlderBuild() = runBlocking {
        val orderId = createCountOrder(target = 2)
        renew(orderId, 1)
        // Simulate the previous build: the mirror was never moved past the old target.
        env.database.orderDao().updateTargetSessions(orderId, 2, env.clock.now())
        assertEquals(2, mirror(orderId))

        // Any real count write re-derives the order through the shared service.
        val period1 = env.database.orderDao().findPeriods(orderId).single { it.periodIndex == 0 }
        val checkbox = env.database.taskDao().findForPeriod(period1.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        env.orderRepository.setOccurrenceCompleted(checkbox.id, true, "N:1")

        assertEquals("the shared re-derivation restores the mirror", 1, mirror(orderId))
        assertEquals(listOf<Int?>(2, 1), periodTargets(orderId))
    }

    /** A stale mirror in an input file is repaired by the reconstruction, never trusted. */
    @Test
    fun reconstructionRepairsAStaleMirrorFromTheLatestPeriod() = runBlocking {
        val orderId = createCountOrder(target = 2)
        renew(orderId, 1)
        val dump = BackupDatabaseAccess(
            ApplicationProvider.getApplicationContext(),
            env.database,
            env.clock,
        ).dump()
        val stale = withMirror(dump, orderId, 2)
        assertEquals("2", rowValue(stale, "orders", orderId, "targetSessions"))

        val rebuilt = DerivedStateReconstructor.reconstruct(stale)

        assertEquals(1, rebuilt.report.orderFlagsChanged)
        assertEquals("1", rowValue(rebuilt.dataSet, "orders", orderId, "targetSessions"))
        assertEquals(
            "period targets are authoritative and unchanged",
            "1",
            rowValue(
                rebuilt.dataSet,
                "hosting_periods",
                env.database.orderDao().findPeriods(orderId).single { it.periodIndex == 1 }.id,
                "serviceCount",
            ),
        )
    }

    /** Period-local completion is unaffected: a completed older period stays completed. */
    @Test
    fun periodLocalCompletionAndAllocationSurviveTheMirrorUpdate() = runBlocking {
        val orderId = createCountOrder(target = 1)
        val period1 = env.database.orderDao().findPeriods(orderId).single()
        val task1 = env.database.taskDao().findForPeriod(period1.id)
            .first { it.progressType == ProgressType.CHECKBOX }
        val cumulative1 = env.database.taskDao().findForPeriod(period1.id)
            .first { it.numericMode == NumericMode.CUMULATIVE }
        val round1 = env.database.hostingSessionDao().findByIndex(orderId, 1)!!

        env.orderRepository.setOccurrenceCompleted(task1.id, true, "N:1")
        progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulative1.id,
                scope = ProgressScope.Session(round1.id),
                attributedGameDate = day1,
                total = BigDecimal("100"),
            ),
        )
        assertNotNull(env.database.hostingSessionDao().findByIndex(orderId, 1)!!.completedAt)
        assertNotNull(env.database.orderDao().findById(orderId)?.completedAt)

        env.clock.set(java.time.Instant.parse("2026-10-18T02:00:00Z"))
        val renewal = renew(orderId, 2)
        progress.updateCountTarget(orderId, renewal.periodId, 3)

        assertEquals(3, mirror(orderId))
        assertEquals(
            "the old period keeps its own completed round",
            1,
            com.daigan.record.data.model.CountHosting.periodCompletedCount(
                env.database.hostingSessionDao().findForOrder(orderId).map { it.toModel() },
                period1.id,
            ),
        )
        assertEquals(day2, env.database.orderDao().findPeriod(renewal.periodId)?.startDate)
    }

    // ---------------------------------------------------------------- helpers

    private fun rowValue(data: BackupDataSet, table: String, id: String, column: String): String? {
        val rows = data.tableRows(table) ?: return null
        val idIndex = rows.table.columnNames.indexOf("id")
        val columnIndex = rows.table.columnNames.indexOf(column)
        if (idIndex < 0 || columnIndex < 0) return null
        val row = rows.rows.firstOrNull { candidate ->
            (candidate.getOrNull(idIndex) as? JsonPrimitive)?.content == id
        } ?: return null
        return (row.getOrNull(columnIndex) as? JsonPrimitive)?.content
    }

    private fun withMirror(data: BackupDataSet, orderId: String, value: Int): BackupDataSet =
        BackupDataSet(
            data.tables.map { tableRows ->
                if (tableRows.table.name != "orders") {
                    tableRows
                } else {
                    val idIndex = tableRows.table.columnNames.indexOf("id")
                    val columnIndex = tableRows.table.columnNames.indexOf("targetSessions")
                    tableRows.copy(
                        rows = tableRows.rows.map { row ->
                            val id = (row.getOrNull(idIndex) as? JsonPrimitive)?.content
                            if (id != orderId) {
                                row
                            } else {
                                row.toMutableList().also {
                                    it[columnIndex] = JsonPrimitive(value.toString())
                                }
                            }
                        },
                    )
                }
            },
        )
}
