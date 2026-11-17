package com.daigan.record.backup

import com.daigan.record.data.local.entity.*
import com.daigan.record.data.model.*
import java.time.Instant
import java.time.LocalDate
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

/**
 * Exercises the restore validator with the exact dual-reference shape written by bulk completion.
 *
 * The validator entry point is reached by reflection because `validateBatches` is private; the
 * parameter list must therefore track the production signature (`batches, items, tasks,
 * occurrences, orders`).
 */
class BackupBatchOwnershipTest {
    private val now = Instant.ofEpochMilli(1000)
    private val day = LocalDate.of(2026, 10, 1)
    private val occurrence = TaskOccurrenceEntity("occurrence", "task", "D:$day", day,
        day, day, now, now, false, "batch", null, now)
    private val batch = CompletionBatchEntity("batch", "operation", BatchScope.HOSTING_ORDER,
        "order", day, now, null)
    private val order = OrderEntity(
        id = "order",
        accountId = "account",
        type = OrderType.HOSTING,
        title = "订单",
        priceCents = null,
        paymentMode = PaymentMode.INSTALLMENTS,
        deadlineDate = null,
        notes = null,
        gameZoneId = "Asia/Shanghai",
        dailyRefreshMinutes = 300,
        weeklyRefreshDay = 1,
        weeklyRefreshMinutes = 300,
        createdAt = now,
        updatedAt = now,
        completedAt = null,
        hostingMode = HostingMode.DATE_RANGE,
    )
    private val task = TaskDefinitionEntity(
        id = "task",
        orderId = "order",
        periodId = "period",
        title = "任务",
        progressType = ProgressType.CHECKBOX,
        startValue = java.math.BigDecimal.ZERO,
        targetValue = java.math.BigDecimal.ONE,
        currentValue = java.math.BigDecimal.ZERO,
        unit = null,
        recurrence = RecurrenceType.DAILY,
        weekdays = null,
        everyNDays = null,
        sortOrder = 0,
        completedAt = null,
        completionBatchId = null,
        createdAt = now,
        updatedAt = now,
    )
    private val item = CompletionBatchItemEntity("item", "batch", BatchItemType.OCCURRENCE,
        "occurrence", "task", null, null, false, null, null, now, now, false, null, "batch")

    private fun validate(
        candidate: CompletionBatchItemEntity,
        occurrences: Map<String, TaskOccurrenceEntity> = mapOf(occurrence.id to occurrence),
        candidateBatch: CompletionBatchEntity = batch,
    ) {
        val method = BackupValidator::class.java.getDeclaredMethod("validateBatches",
            List::class.java, List::class.java, Map::class.java, Map::class.java, Map::class.java)
        method.isAccessible = true
        try {
            method.invoke(BackupValidator(), listOf(candidateBatch), listOf(candidate),
                mapOf(task.id to task), occurrences,
                mapOf(order.id to order))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test fun legacyDualReferencesToSameTaskAreAcceptedWithoutChangingAuditValues() {
        validate(item)
        assertEquals("task", item.taskDefinitionId)
        assertEquals("batch", item.appliedCompletionBatchId)
    }

    @Test fun occurrenceOnlyLegacyRecordsRemainAccepted() { validate(item.copy(taskDefinitionId = null)) }

    @Test fun crossTaskReferencesAreRejected() {
        rejected(item.copy(taskDefinitionId = "another-task"), "任务引用与打卡记录不一致")
    }

    @Test fun missingOccurrenceIsRejectedEvenWithTaskReference() {
        rejected(item.copy(occurrenceId = null), "缺少打卡记录引用")
    }

    @Test fun nonexistentOccurrenceIsRejected() {
        rejected(item.copy(occurrenceId = "missing"), "不存在的打卡记录")
    }

    @Test fun shortTaskCannotClaimAnOccurrence() {
        rejected(item.copy(itemType = BatchItemType.SHORT_TASK), "归属不唯一")
    }

    // ---------------------------------------------------------------- CD-01

    @Test fun undoneBatchMayKeepANullTargetWithItsTaskReference() {
        val undone = batch.copy(undoneAt = now)
        validate(item.copy(occurrenceId = null), candidateBatch = undone)
    }

    @Test fun undoneBatchWithoutTaskReferenceCannotExpressTheHistory() {
        val undone = batch.copy(undoneAt = now)
        rejected(item.copy(occurrenceId = null, taskDefinitionId = null), "缺少打卡记录引用", undone)
    }

    @Test fun undoneBatchCannotKeepADanglingTarget() {
        val undone = batch.copy(undoneAt = now)
        rejected(item.copy(occurrenceId = "missing"), "不存在的打卡记录", undone)
    }

    @Test fun aNullTargetNeedsAnExistingTask() {
        val undone = batch.copy(undoneAt = now)
        rejected(item.copy(occurrenceId = null), "引用了不存在的任务", undone,
            emptyMap(), mapOf(order.id to order))
    }

    private fun rejected(
        candidate: CompletionBatchItemEntity,
        message: String,
        candidateBatch: CompletionBatchEntity = batch,
        taskById: Map<String, TaskDefinitionEntity> = mapOf(task.id to task),
        orderById: Map<String, OrderEntity> = mapOf(order.id to order),
    ) {
        val method = BackupValidator::class.java.getDeclaredMethod("validateBatches",
            List::class.java, List::class.java, Map::class.java, Map::class.java, Map::class.java)
        method.isAccessible = true
        try {
            method.invoke(BackupValidator(), listOf(candidateBatch), listOf(candidate),
                taskById, mapOf(occurrence.id to occurrence), orderById)
            fail("Expected rejection")
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            assertTrue(cause.message.orEmpty(), cause.message.orEmpty().contains(message))
        }
    }
}
