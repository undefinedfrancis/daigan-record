package com.daigan.record.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.testing.shortDraft
import com.daigan.record.testing.numericTask
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskCorrectionTest {
    @Test fun changingARecordedTaskPreservesItsHistoryAndReplacesOnlyFutureWork() = runBlocking {
        val env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>())
        try {
            env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
            val (owner, account) = env.seedOwnerAndAccount()
            val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id,account.id,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31)))
            val before = env.orderRepository.observeOrderDetail(id).first()!!
            val old = before.currentHostingTasks().single().task
            env.orderRepository.setOccurrenceCompleted(old.id,true)
            env.orderRepository.updateTaskDetails(id,listOf(TaskDraft(id=old.id,periodId=old.periodId,title="更正任务",recurrence=RecurrenceType.MONTHLY,monthDay="1,15,20,31"),TaskDraft(periodId=old.periodId,title="新增活动")))
            val after = env.orderRepository.observeOrderDetail(id).first()!!
            assertEquals(old.title, after.historyEntries().single().taskTitle)
            assertEquals(old.id, after.occurrences.single().taskDefinitionId)
            assertEquals(setOf("更正任务","新增活动"), after.currentHostingTasks().map { it.task.title }.toSet())
            assertFalse(after.currentHostingTasks().any { it.task.id == old.id })
            assertEquals(before.periods.map { it.priceCents },after.periods.map { it.priceCents })
        } finally { env.close() }
    }
    @Test fun renamingCompletedHostingTaskPreservesIdentityAndCompletion() = runBlocking {
        val env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>())
        try {
            env.clock.set(Instant.parse("2026-10-20T02:00:00Z"))
            val (owner, account) = env.seedOwnerAndAccount()
            val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id,account.id,LocalDate.of(2026,10,1),LocalDate.of(2026,10,31)))
            val old = env.orderRepository.observeOrderDetail(id).first()!!.currentHostingTasks().single().task
            env.orderRepository.setOccurrenceCompleted(old.id,true)
            env.orderRepository.updateTaskDetails(id,listOf(TaskDraft(id=old.id,periodId=old.periodId,title="更正名称")))
            val after = env.orderRepository.observeOrderDetail(id).first()!!
            assertEquals(old.id, after.currentHostingTasks().single().task.id)
            assertNotNull(after.occurrences.single().completedAt)
            assertEquals(old.id,after.occurrences.single().taskDefinitionId)
        } finally { env.close() }
    }

    @Test fun numericMetadataAndTargetCorrectionRetainActualProgress() = runBlocking {
        val env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>())
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val id = env.orderRepository.createShortOrder(shortDraft(owner.id,account.id,tasks=listOf(numericTask())))
            val old = env.orderRepository.observeOrderDetail(id).first()!!.tasks.single()
            env.orderRepository.updateShortTaskProgress(old.id,BigDecimal("50"))
            env.orderRepository.updateTaskDetails(id,listOf(numericTask(title="修正名称",target="40").copy(id=old.id)))
            val after = env.orderRepository.observeOrderDetail(id).first()!!
            val task = after.tasks.single { it.retiredAt == null }
            assertEquals(old.id, task.id)
            assertEquals(0,BigDecimal("50").compareTo(task.currentValue))
            assertNotNull(task.completedAt)
            assertNotNull(after.order.completedAt)
        } finally { env.close() }
    }

    @Test fun renamingCompletedShortTaskDoesNotReopenOrder() = runBlocking {
        val env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>())
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val id = env.orderRepository.createShortOrder(shortDraft(owner.id,account.id))
            val old = env.orderRepository.observeOrderDetail(id).first()!!.tasks.single()
            env.orderRepository.setShortTaskCompleted(old.id,true)
            env.orderRepository.updateTaskDetails(id,listOf(TaskDraft(id=old.id,title="改正名称")))
            val after = env.orderRepository.observeOrderDetail(id).first()!!
            assertEquals(old.id,after.tasks.single().id)
            assertNotNull(after.tasks.single().completedAt)
            assertNotNull(after.order.completedAt)
        } finally { env.close() }
    }

    @Test fun targetCorrectionProtectsLatestResultFromAnOlderBatchUndo() = runBlocking {
        val env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>())
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val id = env.orderRepository.createShortOrder(shortDraft(owner.id,account.id,tasks=listOf(numericTask())))
            val task = env.orderRepository.observeOrderDetail(id).first()!!.tasks.single()
            env.orderRepository.updateShortTaskProgress(task.id,BigDecimal("50"))
            val batch = env.orderRepository.completeShortOrder(id,"before-target-correction")
            env.orderRepository.updateTaskDetails(id,listOf(numericTask(target="40").copy(id=task.id)))
            val undo = env.orderRepository.undoBatch(requireNotNull(batch.batchId))
            assertEquals(0,undo.restoredCount)
            val after = env.orderRepository.observeOrderDetail(id).first()!!
            assertEquals(0,BigDecimal("100").compareTo(after.tasks.single().currentValue))
            assertNotNull(after.order.completedAt)
        } finally { env.close() }
    }

}
