package com.daigan.record.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.data.model.RecurrenceType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchedulingFeaturesTest {
    @Test fun accumulatedServiceDaysSkipWeekendsAndSurviveRenewal() = runBlocking {
        val env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>())
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
            env.clock.set(Instant.parse("2026-10-17T02:00:00Z"))
            val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id,
                LocalDate.of(2026,10,16), LocalDate.of(2026,10,22)).copy(serviceDays = 5, serviceWeekdays = days))
            assertTrue("Saturday is not a planned service day", env.orderRepository.observeTodayHosting().first().single().tasks.isEmpty())
            env.clock.set(Instant.parse("2026-10-19T02:00:00Z"))
            assertEquals(1, env.orderRepository.observeTodayHosting().first().single().tasks.size)
            val renewal = env.orderRepository.renewHosting(id, "workdays-renew", 5, 10000)
            assertEquals(LocalDate.of(2026,10,23), renewal.startDate)
            assertEquals(LocalDate.of(2026,10,29), renewal.endDate)
        } finally { env.close() }
    }
    @Test fun monthlyOneAndFifteenGenerateDistinctDaysAndRestoreConfiguration() = runBlocking {
        val env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>())
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val draft = hostingDraft(owner.id, account.id, LocalDate.of(2026,10,1), LocalDate.of(2026,12,31),
                tasks = listOf(TaskDraft(title="活动", recurrence=RecurrenceType.MONTHLY, monthDay="15,1,15,31")))
            assertTrue("multiple monthly dates must be accepted", draft.validate().isEmpty())
            env.orderRepository.createHostingOrder(draft)
            for ((date, count) in listOf("2026-10-15" to 1, "2026-10-16" to 0, "2026-10-31" to 1, "2026-11-01" to 1, "2026-11-30" to 0)) {
                env.clock.set(Instant.parse(date+"T02:00:00Z"))
                assertEquals(date, count, env.orderRepository.observeTodayHosting().first().single().tasks.size)
            }
        } finally { env.close() }
    }
    @Test fun servicePlanCanBeCorrectedWithoutChangingPriceOrOverlappingFuturePeriods() = runBlocking {
        val env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>())
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            env.clock.set(Instant.parse("2026-10-19T02:00:00Z"))
            val id = env.orderRepository.createHostingOrder(hostingDraft(owner.id,account.id,LocalDate.of(2026,10,16),LocalDate.of(2026,10,31)))
            val p = env.orderRepository.observeOrderDetail(id).first()!!.currentPeriod!!
            env.orderRepository.updateServiceSchedule(id,p.id,5,setOf(DayOfWeek.MONDAY,DayOfWeek.TUESDAY,DayOfWeek.WEDNESDAY,DayOfWeek.THURSDAY,DayOfWeek.FRIDAY))
            val changed = env.orderRepository.observeOrderDetail(id).first()!!.currentPeriod!!
            assertEquals(LocalDate.of(2026,10,22),changed.endDate)
            assertEquals(5,changed.serviceDays)
            assertEquals(p.priceCents,changed.priceCents)
            env.orderRepository.renewHosting(id,"future",5,10000)
            val failure = runCatching { env.orderRepository.updateServiceSchedule(id,p.id,15,changed.serviceWeekdays) }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(LocalDate.of(2026,10,22),env.orderRepository.observeOrderDetail(id).first()!!.currentPeriod!!.endDate)
        } finally { env.close() }
    }

}
