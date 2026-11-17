package com.daigan.record.review

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.OrderType
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.shortDraft
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UF-031: an archived owner can be deleted directly, with the real impact reported first,
 * and only that owner's rows are removed.
 */
@RunWith(AndroidJUnit4::class)
class OwnerDeletionTest {

    private lateinit var env: OrderTestEnvironment

    @Before
    fun setUp() {
        env = OrderTestEnvironment(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun archivedOwnerDeletionRemovesOnlyItsOwnData() = runBlocking {
        val (ownerA, accountA) = env.seedOwnerAndAccount(nickname = "甲老板", uid = "uid-a")
        val (ownerB, accountB) = env.seedOwnerAndAccount(nickname = "乙老板", uid = "uid-b")

        val orderA = env.orderRepository.createShortOrder(
            shortDraft(ownerA.id, accountA.id, title = "甲的短期单"),
        )
        env.orderRepository.createShortOrder(
            shortDraft(ownerB.id, accountB.id, title = "乙的短期单"),
        )
        // Complete A's only task so the owner can be archived (unfinished short work blocks it).
        val detailA = env.orderRepository.observeOrderDetail(orderA).first()!!
        env.orderRepository.setShortTaskCompleted(detailA.tasks.single().id, true)

        env.ownerRepository.archiveOwner(ownerA.id)

        // Reading the impact must not change anything.
        val impact = env.ownerRepository.ownerDeletionImpact(ownerA.id)
        assertEquals(1, impact.accountCount)
        assertEquals(1, impact.orderCount)
        assertNotNull(env.ownerRepository.findOwner(ownerA.id))

        val summary = env.ownerRepository.deleteArchivedOwner(ownerA.id)
        assertEquals(1, summary.deletedAccountCount)
        assertEquals(1, summary.deletedOrderCount)

        assertNull(env.ownerRepository.findOwner(ownerA.id))
        assertNull(env.accountRepository.findAccount(accountA.id))
        assertNull(env.orderRepository.observeOrderDetail(orderA).first())

        // The other owner is untouched.
        assertNotNull(env.ownerRepository.findOwner(ownerB.id))
        assertNotNull(env.accountRepository.findAccount(accountB.id))
        assertEquals(1, env.orderRepository.observeOrdersForOwner(ownerB.id).first().size)
    }

    @Test
    fun activeOwnerCannotBeDeleted() = runBlocking {
        val (owner, _) = env.seedOwnerAndAccount(nickname = "未归档老板", uid = "uid-c")
        try {
            env.ownerRepository.deleteArchivedOwner(owner.id)
            fail("An active owner must not be deletable")
        } catch (_: IllegalArgumentException) {
            // Documented behaviour: archive first, or keep the record.
        }
        assertNotNull(env.ownerRepository.findOwner(owner.id))
    }

    @Test
    fun deletingOwnerAlsoRemovesCountBasedRounds() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount(nickname = "打卡老板", uid = "uid-d")
        val orderId = env.orderRepository.createHostingOrder(
            hostingDraft(
                ownerId = owner.id,
                accountId = account.id,
                start = LocalDate.of(2026, 10, 17),
                end = LocalDate.of(2026, 10, 17),
                tasks = listOf(com.daigan.record.data.model.TaskDraft(title = "签到")),
            ).copy(hostingMode = HostingMode.COUNT_BASED, targetSessions = 1),
        )
        val detail = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(1, detail.sessions.size)
        // One round: complete its only task, which reaches the agreed target.
        val task = detail.currentHostingTasks().single()
        env.orderRepository.setOccurrenceCompleted(task.task.id, true, task.slot.key)
        val completed = env.orderRepository.observeOrderDetail(orderId).first()!!
        assertEquals(1, completed.completedSessionCount)
        assertTrue(completed.countSessionsComplete)
        assertEquals(OrderType.HOSTING, completed.order.type)

        env.orderRepository.archiveExpiredHostingOrder(orderId)
        env.ownerRepository.archiveOwner(owner.id)
        env.ownerRepository.deleteArchivedOwner(owner.id)

        assertEquals(0, env.database.hostingSessionDao().countForOrder(orderId))
        assertNull(env.orderRepository.observeOrderDetail(orderId).first())
    }
}
