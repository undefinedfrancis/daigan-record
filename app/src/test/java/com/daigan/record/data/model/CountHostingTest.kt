package com.daigan.record.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UF-033 counting rules, verified without a database.
 *
 * These are the contract points the repository and the screens rely on: a round (not a
 * day) is the identity, an individual task never counts, duplicates never double the
 * total, and undo reopens a round instead of deleting history.
 */
class CountHostingTest {

    private val createdAt = Instant.parse("2026-10-21T02:00:00Z")
    private val completedAt = Instant.parse("2026-10-21T03:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun session(index: Int, completed: Boolean, completedInstant: Instant = completedAt) =
        HostingSession(
            id = "session-$index",
            orderId = "order-1",
            sessionIndex = index,
            createdAt = createdAt,
            completedAt = if (completed) completedInstant else null,
        )

    @Test
    fun roundsOnTheSameDayAreSeparateIdentities() {
        // Two rounds completed at the same instant (same game day) are still two counts.
        val sessions = listOf(session(1, true), session(2, true))
        assertEquals(2, CountHosting.completedCount(sessions))
        // Both rounds were completed on the same game day and both count.
        assertEquals(2, CountHosting.completedCountOn(sessions, LocalDate.of(2026, 10, 21), zone))
        assertEquals(0, CountHosting.completedCountOn(sessions, LocalDate.of(2026, 10, 22), zone))
        assertTrue(
            CountHosting.occurrenceKey(1) != CountHosting.occurrenceKey(2),
        )
    }

    @Test
    fun individualTaskDoesNotCompleteRound() {
        assertFalse(CountHosting.roundCompleted(setOf("a", "b"), setOf("a")))
        assertTrue(CountHosting.roundCompleted(setOf("a", "b"), setOf("a", "b")))
        // An order without tasks can never earn a round by accident.
        assertFalse(CountHosting.roundCompleted(emptySet(), emptySet()))
    }

    @Test
    fun aDayWithoutActivityConsumesNothing() {
        val sessions = listOf(session(1, false))
        assertEquals(0, CountHosting.completedCount(sessions))
        assertEquals(30, CountHosting.remaining(sessions, 30))
        assertEquals(1, CountHosting.activeSessionIndex(sessions, 30))
        assertFalse(CountHosting.isComplete(sessions, 30))
    }

    @Test
    fun duplicateRowsCannotDoubleCount() {
        // Even a corrupted duplicate of round 1 counts once.
        val sessions = listOf(session(1, true), session(1, true))
        assertEquals(1, CountHosting.completedCount(sessions))
    }

    @Test
    fun nextRoundStartsAfterThePreviousOne() {
        val sessions = listOf(session(1, true))
        assertEquals(2, CountHosting.activeSessionIndex(sessions, 30))
        assertEquals(2, CountHosting.nextSessionIndex(sessions, 30))
        assertEquals(29, CountHosting.remaining(sessions, 30))
    }

    @Test
    fun reachingTargetCompletesAndStartsNoFurtherRound() {
        val sessions = listOf(session(1, true), session(2, true), session(3, true))
        assertTrue(CountHosting.isComplete(sessions, 3))
        assertNull(CountHosting.activeSessionIndex(sessions, 3))
        assertNull(CountHosting.nextSessionIndex(sessions, 3))
        assertEquals(0, CountHosting.remaining(sessions, 3))
    }

    @Test
    fun reopenedEarlierRoundIsFinishedBeforeLaterWork() {
        // Undo reopened round 2 while round 3 was already counted: history stays, the
        // total drops by one, and the reopened round is the one to work on.
        val sessions = listOf(session(1, true), session(2, false), session(3, true))
        assertEquals(2, CountHosting.completedCount(sessions))
        assertEquals(2, CountHosting.activeSessionIndex(sessions, 30))
        assertEquals(3, sessions.size)
    }

    @Test
    fun reopeningKeepsTheTotalConsistentWithHistory() {
        val before = listOf(session(1, true), session(2, true))
        assertEquals(2, CountHosting.completedCount(before))
        val afterUndo = listOf(session(1, true), session(2, false))
        assertEquals(1, CountHosting.completedCount(afterUndo))
        assertEquals(2, CountHosting.remaining(afterUndo, 3))
        assertEquals(2, afterUndo.size)
    }

    @Test
    fun occurrenceKeysRoundTripOnlyForCountRounds() {
        assertEquals(7, CountHosting.sessionIndexOrNull("N:7"))
        assertEquals("N:7", CountHosting.occurrenceKey(7))
        assertNull(CountHosting.sessionIndexOrNull("D:2026-10-21"))
        assertNull(CountHosting.sessionIndexOrNull("N:0"))
        assertNull(CountHosting.sessionIndexOrNull("N:"))
    }

    @Test
    fun targetValidationUsesChineseMessages() {
        assertEquals("请填写打卡次数", CountHosting.targetSessionError(""))
        assertEquals("打卡次数需为整数", CountHosting.targetSessionError("abc"))
        assertEquals("打卡次数需大于 0", CountHosting.targetSessionError("0"))
        assertEquals(
            "打卡次数最多 ${CountHosting.MAX_TARGET_SESSIONS} 次",
            CountHosting.targetSessionError("${CountHosting.MAX_TARGET_SESSIONS + 1}"),
        )
        assertNull(CountHosting.targetSessionError("30"))
    }
}
