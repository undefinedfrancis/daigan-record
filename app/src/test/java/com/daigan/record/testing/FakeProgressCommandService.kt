package com.daigan.record.testing

import com.daigan.record.data.local.entity.HostingSessionEntity
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.TaskProgressEvent
import com.daigan.record.data.repository.CorrectTotalCommand
import com.daigan.record.data.repository.EnsureSessionResult
import com.daigan.record.data.repository.ProgressCommandResult
import com.daigan.record.data.repository.ProgressCommandService
import com.daigan.record.data.repository.ProgressSnapshot
import com.daigan.record.data.repository.SubmitTotalCommand
import java.time.Instant

/**
 * JVM stand-in for [ProgressCommandService].
 *
 * It keeps the only contract the ViewModels rely on: commands are accepted, history is
 * readable, and a retried `operationId` replays instead of appending. It is deliberately
 * not a second implementation of the business rules — those live in the Room repository
 * and its pure helpers.
 */
class FakeProgressCommandService : ProgressCommandService {

    val events = mutableListOf<TaskProgressEvent>()
    val submittedCommands = mutableListOf<SubmitTotalCommand>()
    val correctedCommands = mutableListOf<CorrectTotalCommand>()
    var sessionIndex: Int = 1
    var failWith: Throwable? = null
    var ensureCalls: Int = 0
    private var sequence = 0

    override suspend fun submitTotal(command: SubmitTotalCommand): ProgressCommandResult {
        failWith?.let { throw it }
        submittedCommands += command
        val existing = events.firstOrNull { it.operationId == command.operationId }
        if (existing != null) return ProgressCommandResult.IdempotentReplay(existing, snapshotFor(existing))
        val event = TaskProgressEvent(
            id = "event-${sequence + 1}",
            taskDefinitionId = command.taskId,
            operationId = command.operationId,
            kind = com.daigan.record.data.model.ProgressEventKind.SUBMIT,
            scope = command.scope,
            gameDate = command.attributedGameDate,
            totalValue = command.total,
            sequence = ++sequence,
            correctsEventId = null,
            recordedAt = Instant.parse("2026-10-22T02:00:00Z"),
            createdAt = Instant.parse("2026-10-22T02:00:00Z"),
        )
        events += event
        return ProgressCommandResult.Applied(event, snapshotFor(event))
    }

    override suspend fun correctTotal(command: CorrectTotalCommand): ProgressCommandResult {
        failWith?.let { throw it }
        correctedCommands += command
        val target = events.firstOrNull { it.id == command.targetEventId }
            ?: throw IllegalStateException("被更正的事件不存在")
        val event = TaskProgressEvent(
            id = "event-${sequence + 1}",
            taskDefinitionId = command.taskId,
            operationId = command.operationId,
            kind = if (command.total == null) {
                com.daigan.record.data.model.ProgressEventKind.RETRACT
            } else {
                com.daigan.record.data.model.ProgressEventKind.CORRECT
            },
            scope = target.scope,
            gameDate = target.gameDate,
            totalValue = command.total,
            sequence = ++sequence,
            correctsEventId = target.id,
            recordedAt = Instant.parse("2026-10-22T02:00:00Z"),
            createdAt = Instant.parse("2026-10-22T02:00:00Z"),
        )
        events += event
        return ProgressCommandResult.Applied(event, snapshotFor(event))
    }

    override suspend fun ensureCountSession(
        orderId: String,
        requestedSessionIndex: Int?,
        expectedGeneration: Long?,
    ): EnsureSessionResult {
        ensureCalls += 1
        failWith?.let { throw it }
        val index = requestedSessionIndex ?: sessionIndex
        return EnsureSessionResult(
            session = HostingSessionEntity(
                id = "session-$index",
                orderId = orderId,
                sessionIndex = index,
                createdAt = Instant.parse("2026-10-22T02:00:00Z"),
                completedAt = null,
                completionBatchId = null,
                periodId = "period-1",
            ),
            created = false,
        )
    }

    override suspend fun progressHistory(taskId: String): List<TaskProgressEvent> =
        events.filter { it.taskDefinitionId == taskId }.sortedBy { it.sequence }

    override suspend fun cumulativeStatus(taskId: String, scope: ProgressScope): ProgressSnapshot? {
        val latest = progressHistory(taskId).lastOrNull { it.scope == scope } ?: return null
        return snapshotFor(latest)
    }

    override suspend fun countSessionHistory(
        orderId: String,
    ): List<com.daigan.record.data.repository.CountSessionHistoryEntry> {
        sessions += orderId
        return historyEntries
    }

    override suspend fun resolveActiveCountSession(
        orderId: String,
    ): com.daigan.record.data.model.CountHosting.ActiveCountState = activeState

    /** Sessions the test wants the panel to offer; empty means "no count history". */
    val historyEntries =
        mutableListOf<com.daigan.record.data.repository.CountSessionHistoryEntry>()

    var activeState: com.daigan.record.data.model.CountHosting.ActiveCountState =
        com.daigan.record.data.model.CountHosting.ActiveCountState.AllPeriodsComplete

    val sessions = mutableListOf<String>()

    private fun snapshotFor(event: TaskProgressEvent): ProgressSnapshot = ProgressSnapshot(
        taskId = event.taskDefinitionId,
        scope = event.scope,
        total = event.totalValue ?: java.math.BigDecimal.ZERO,
        reachedTarget = false,
        effectiveEventId = event.id,
        taskCurrentValue = event.totalValue ?: java.math.BigDecimal.ZERO,
        taskCompletedAt = null,
    )
}
