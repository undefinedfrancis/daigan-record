package com.daigan.record.review

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daigan.record.data.model.*
import com.daigan.record.testing.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class Stage2AcceptanceTest {
 private lateinit var env: OrderTestEnvironment
 @Before fun setup() { env=OrderTestEnvironment(InstrumentationRegistry.getInstrumentation().targetContext) }
 @After fun teardown() { env.close() }
 private suspend fun detail(id:String)=requireNotNull(env.orderRepository.observeOrderDetail(id).first())
 @Test fun unifiedRenameDoesNotCreatePriceHistory()=runBlocking {
  val (o,a)=env.seedOwnerAndAccount()
  val id=env.orderRepository.createShortOrder(shortDraft(ownerId=o.id,accountId=a.id))
  env.orderRepository.applyOrderEdit(OrderEditDraft.of(detail(id)).copy(title="改名"))
  assertEquals("改名",detail(id).order.title)
  assertTrue("Renaming without changing price must not invent a price-correction history",env.database.orderDao().findPriceChanges(id).isEmpty())
 }
 @Test fun expiredHostingStillAllowsBasicMetadataCorrection()=runBlocking {
  val (o,a)=env.seedOwnerAndAccount()
  val id=env.orderRepository.createHostingOrder(hostingDraft(ownerId=o.id,accountId=a.id,start=LocalDate.of(2020,1,1),end=LocalDate.of(2020,1,3)))
  env.orderRepository.applyOrderEdit(OrderEditDraft.of(detail(id)).copy(title="历史名称纠正"))
  assertEquals("历史名称纠正",detail(id).order.title)
 }
 @Test fun raisingCountTargetReopensCompletedOrder()=runBlocking {
  val (o,a)=env.seedOwnerAndAccount()
  val id=env.orderRepository.createHostingOrder(hostingDraft(ownerId=o.id,accountId=a.id,start=LocalDate.of(2026,10,1),end=LocalDate.of(2026,10,1)).copy(hostingMode=HostingMode.COUNT_BASED,targetSessions=1))
  detail(id).currentHostingTasks().forEach { env.orderRepository.setOccurrenceCompleted(it.task.id,true,it.slot.key) }
  assertNotNull(detail(id).order.completedAt)
  env.orderRepository.applyOrderEdit(OrderEditDraft.of(detail(id)).copy(targetSessions=2))
  assertFalse(detail(id).countSessionsComplete)
  assertNull("Increasing target above completed sessions must clear completedAt",detail(id).order.completedAt)
 }
 @Test fun invalidTaskRollsBackEarlierMetadataWrites()=runBlocking {
  val (o,a)=env.seedOwnerAndAccount()
  val id=env.orderRepository.createShortOrder(shortDraft(ownerId=o.id,accountId=a.id))
  val before=detail(id)
  val draft=OrderEditDraft.of(before).copy(title="不应保存",tasks=listOf(TaskDraft(title="")))
  assertTrue(runCatching { env.orderRepository.applyOrderEdit(draft) }.isFailure)
  assertEquals(before.order.title,detail(id).order.title)
  assertTrue(env.database.orderDao().findPriceChanges(id).isEmpty())
 }
 @Test fun orderColorsStayIndependentUnderSameAccount()=runBlocking {
  val (o,a)=env.seedOwnerAndAccount()
  val x=env.orderRepository.createShortOrder(shortDraft(ownerId=o.id,accountId=a.id))
  val y=env.orderRepository.createShortOrder(shortDraft(ownerId=o.id,accountId=a.id))
  env.orderRepository.setOrderColor(x,0xFFE53935.toInt())
  assertNull(detail(y).order.colorArgb)
  env.orderRepository.setOrderColor(y,0xFF1E88E5.toInt())
  env.orderRepository.setOrderColor(x,null)
  assertNull(detail(x).order.colorArgb)
  assertEquals(0xFF1E88E5.toInt(),detail(y).order.colorArgb)
 }
}
