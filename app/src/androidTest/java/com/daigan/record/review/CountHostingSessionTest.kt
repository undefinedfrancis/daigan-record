package com.daigan.record.review

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.feature.orders.toDisplayedSnapshot
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UF-033 repository contract for 打卡式托管: one round counts exactly once, an individual
 * task never counts, the same day can hold several rounds, undo reopens the round and the
 * total stays consistent.
 */
@RunWith(AndroidJUnit4::class)
class CountHostingSessionTest {

    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() {
        env = OrderTestEnvironment(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() {
        env.close()
    }

    private suspend fun createCountOrder(target: Int, taskCount: Int = 2): String {
        val (owner, account) = env.seedOwnerAndAccount()
        val tasks = (1..taskCount).map { TaskDraft(title = "任务 $it") }
        return env.orderRepository.createHostingOrder(
            hostingDraft(
                ownerId = owner.id,
                accountId = account.id,
                start = LocalDate.of(2026, 10, 17),
                end = LocalDate.of(2026, 10, 17),
                tasks = tasks,
            ).copy(hostingMode = HostingMode.COUNT_BASED, targetSessions = target),
        )
    }

    private suspend fun detail(orderId: String) =
        requireNotNull(env.orderRepository.observeOrderDetail(orderId).first())

    private suspend fun completeRound(orderId: String) {
        val current = detail(orderId)
        current.currentHostingTasks().forEach { task ->
            env.orderRepository.setOccurrenceCompleted(task.task.id, true, task.slot.key)
        }
    }

    @Test
    fun singleTaskNeverCountsAndRepeatedTapsCountOnce() = runBlocking {
        val orderId = createCountOrder(target = 3, taskCount = 2)
        val started = detail(orderId)
        assertEquals(0, started.completedSessionCount)
        assertEquals(3, started.targetSessionCount)
        assertEquals("only the materialised round exists", 1, started.sessions.size)
        assertEquals(1, started.activeSession?.sessionIndex ?: -1)
        assertEquals("round 1 is a persisted open row", -1, started.awaitingSessionIndex ?: -1)

        val tasks = started.currentHostingTasks()
        // Completing only the first task of the round does not move the total.
        env.orderRepository.setOccurrenceCompleted(tasks[0].task.id, true, tasks[0].slot.key)
        assertEquals(0, detail(orderId).completedSessionCount)
        assertEquals(1, detail(orderId).sessions.size)

        // Completing the second task completes exactly one round.
        env.orderRepository.setOccurrenceCompleted(tasks[1].task.id, true, tasks[1].slot.key)
        val afterRound = detail(orderId)
        assertEquals(1, afterRound.completedSessionCount)
        assertEquals(2, afterRound.remainingSessionCount)
        // Rounds are created lazily by their own work: finishing round 1 leaves no round-2
        // row, so the persisted set still holds exactly one row.
        assertEquals("no eager row for the next round", 1, afterRound.sessions.size)
        assertTrue(afterRound.sessions.single().completed)
        assertNull("there is no open persisted row", afterRound.activeSession)
        assertEquals("the awaiting state names the next round", 2, afterRound.awaitingSessionIndex ?: -1)
        assertEquals(2, afterRound.activeSessionIndex ?: -1)

        // Reading never materialises the pending round.
        repeat(3) { assertEquals(1, detail(orderId).sessions.size) }

        // A repeated/stale tap on the same displayed key cannot count another round.
        env.orderRepository.setOccurrenceCompleted(tasks[1].task.id, true, tasks[1].slot.key)
        val afterStaleTap = detail(orderId)
        assertEquals(1, afterStaleTap.completedSessionCount)
        assertEquals(1, afterStaleTap.sessions.size)
    }

    @Test
    fun twoRoundsCanBeCompletedOnTheSameDay() = runBlocking {
        val orderId = createCountOrder(target = 3, taskCount = 2)
        completeRound(orderId)
        assertEquals(1, detail(orderId).completedSessionCount)
        assertEquals("only the materialised round exists", 1, detail(orderId).sessions.size)

        // The same calendar day holds the second round: a round, not a date, is identity.
        completeRound(orderId)
        val result = detail(orderId)
        assertEquals(2, result.completedSessionCount)
        assertEquals("one row per actually completed round", 2, result.sessions.size)
        assertEquals(setOf(1, 2), result.sessions.map { it.sessionIndex }.toSet())
        assertTrue(result.sessions.all { it.completed })
        assertEquals(1, result.remainingSessionCount)
        assertNull(result.activeSession)
        // Round 3 is allocated but not written: no row exists for it yet.
        assertEquals(3, result.awaitingSessionIndex ?: -1)
        assertEquals(3, result.activeSessionIndex ?: -1)
        // Reading again does not create it either.
        assertEquals(2, detail(orderId).sessions.size)
    }

    @Test
    fun reachingTargetCompletesTheOrder() = runBlocking {
        val orderId = createCountOrder(target = 2, taskCount = 2)
        completeRound(orderId)
        assertNull(detail(orderId).order.completedAt)
        completeRound(orderId)
        val done = detail(orderId)
        assertTrue(done.countSessionsComplete)
        assertNotNull(done.order.completedAt)
        assertEquals(0, done.remainingSessionCount)
        assertTrue(done.currentHostingTasks().isEmpty())
        // Completion alone makes the order archivable, with no date passing.
        env.orderRepository.archiveExpiredHostingOrder(orderId)
        assertTrue(detail(orderId).order.archived)
    }

    @Test
    fun undoRestoresTheRoundWithoutDeletingHistory() = runBlocking {
        val orderId = createCountOrder(target = 3, taskCount = 2)
        val before = detail(orderId)
        val snapshot = requireNotNull(before.toDisplayedSnapshot())
        val operationId = "count-batch-1"
        val batch = env.orderRepository.completeHostingOrder(orderId, operationId, snapshot)
        val counted = detail(orderId)
        assertEquals(1, counted.completedSessionCount)
        val countedRound = counted.sessions.single()
        assertEquals(1, countedRound.sessionIndex)
        assertTrue(countedRound.completed)

        // Retrying the same batch command is idempotent.
        val retried = env.orderRepository.completeHostingOrder(orderId, operationId, snapshot)
        assertTrue(retried.alreadyApplied)
        assertEquals(1, detail(orderId).completedSessionCount)
        assertEquals("an identical retry writes no second round", 1, detail(orderId).sessions.size)

        env.orderRepository.undoBatch(batch.batchId)
        val undone = detail(orderId)
        assertEquals(0, undone.completedSessionCount)
        // Undo restores the round instead of deleting it: the counted row keeps its id, and
        // no later round row was ever created for the unwritten next index.
        val byIndex = undone.sessions.associateBy { it.sessionIndex }
        assertEquals(setOf(1), byIndex.keys)
        assertEquals("the counted row is kept as history", countedRound.id, byIndex.getValue(1).id)
        assertFalse("round 1 must reopen", byIndex.getValue(1).completed)
        assertEquals(3, undone.remainingSessionCount)
        assertEquals(1, undone.activeSession?.sessionIndex)
        assertEquals(-1, undone.awaitingSessionIndex ?: -1)
        // The round-1 occurrences are a restorable state, not a deletion.
        assertEquals(
            2,
            env.database.occurrenceDao().findForOrder(orderId)
                .count { it.occurrenceKey == "N:1" },
        )

        // Undoing the same batch again is a no-op: the total cannot drop twice.
        val repeated = env.orderRepository.undoBatch(batch.batchId)
        assertTrue(repeated.alreadyUndone)
        assertEquals(0, detail(orderId).completedSessionCount)
    }

    @Test
    fun undoingRoundOneKeepsLaterRoundProgress() = runBlocking {
        val orderId = createCountOrder(target = 4, taskCount = 2)
        val firstSnapshot = requireNotNull(detail(orderId).toDisplayedSnapshot())
        val batch = env.orderRepository.completeHostingOrder(orderId, "count-batch-later", firstSnapshot)
        assertEquals(1, detail(orderId).completedSessionCount)
        assertEquals("round 2 is allocated but has no row yet", 2, detail(orderId).awaitingSessionIndex ?: -1)

        // Start round 2 before correcting round 1.
        val roundTwo = detail(orderId).currentHostingTasks()
        assertEquals(2, roundTwo.size)
        assertEquals("N:2", roundTwo[0].slot.key)
        env.orderRepository.setOccurrenceCompleted(roundTwo[0].task.id, true, roundTwo[0].slot.key)
        assertEquals(1, detail(orderId).completedSessionCount)
        // Half a round is not a round: it never materialises a row on its own.
        assertEquals("only completed rounds persist a row", 1, detail(orderId).sessions.size)

        env.orderRepository.undoBatch(batch.batchId)
        val undone = detail(orderId)
        assertEquals(0, undone.completedSessionCount)
        assertEquals(4, undone.remainingSessionCount)
        val byIndex = undone.sessions.associateBy { it.sessionIndex }
        assertEquals(setOf(1), byIndex.keys)
        assertFalse(byIndex.getValue(1).completed)
        // Round 1 is the oldest unfinished round again.
        assertEquals(1, undone.activeSession?.sessionIndex)
        // Round 2's partial progress is not collateral damage.
        val roundTwoProgress = env.database.occurrenceDao().findForOrder(orderId)
            .filter { it.occurrenceKey == "N:2" && it.completedAt != null }
            .map { it.taskDefinitionId }
            .toSet()
        assertEquals(setOf(roundTwo[0].task.id), roundTwoProgress)

        // A repeated undo leaves both the total and round 2's progress untouched.
        env.orderRepository.undoBatch(batch.batchId)
        assertEquals(0, detail(orderId).completedSessionCount)
        assertEquals(
            setOf(roundTwo[0].task.id),
            env.database.occurrenceDao().findForOrder(orderId)
                .filter { it.occurrenceKey == "N:2" && it.completedAt != null }
                .map { it.taskDefinitionId }
                .toSet(),
        )
    }

    /** A later round that was already counted is not collateral damage of an earlier undo. */
    @Test
    fun undoingAnEarlierRoundKeepsACompletedLaterRound() = runBlocking {
        val orderId = createCountOrder(target = 4, taskCount = 2)
        val firstSnapshot = requireNotNull(detail(orderId).toDisplayedSnapshot())
        val batch = env.orderRepository.completeHostingOrder(orderId, "count-batch-early", firstSnapshot)
        assertEquals(1, detail(orderId).completedSessionCount)
        completeRound(orderId)
        val both = detail(orderId)
        assertEquals(2, both.completedSessionCount)
        assertEquals(2, both.sessions.size)
        val roundTwo = both.sessions.single { it.sessionIndex == 2 }
        assertTrue(roundTwo.completed)

        env.orderRepository.undoBatch(batch.batchId)
        val undone = detail(orderId)
        assertEquals("only round 1 is reopened", 1, undone.completedSessionCount)
        assertEquals(both.sessions.map { it.id }.toSet(), undone.sessions.map { it.id }.toSet())
        assertFalse(undone.sessions.single { it.sessionIndex == 1 }.completed)
        assertTrue(
            "round 2 keeps its completion",
            undone.sessions.single { it.sessionIndex == 2 }.completed,
        )
        assertEquals(1, undone.activeSession?.sessionIndex)
    }

    @Test
    fun reopeningACompletedRoundKeepsTheRowAndDropsTheTotal() = runBlocking {
        val orderId = createCountOrder(target = 2, taskCount = 2)
        completeRound(orderId)
        completeRound(orderId)
        assertTrue(detail(orderId).countSessionsComplete)

        env.orderRepository.reopenCountSession(orderId, sessionIndex = 2)
        val reopened = detail(orderId)
        assertEquals(1, reopened.completedSessionCount)
        assertEquals(2, reopened.sessions.size)
        assertFalse(reopened.sessions.single { it.sessionIndex == 2 }.completed)
        assertNull(reopened.order.completedAt)
        // Round 2 is the one to work on again.
        assertEquals(2, reopened.activeSession?.sessionIndex)
        assertEquals(2, reopened.currentHostingTasks().size)
    }
}
