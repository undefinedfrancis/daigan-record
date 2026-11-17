package com.daigan.record.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingDraft
import com.daigan.record.testing.shortDraft
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CancelledAccountDeletionImpactTest {
    @Test fun cancelledShortAndHostingDebtExcludedWhileActualReceiptsAndRemovalCountsRemain() = runBlocking {
        val env = OrderTestEnvironment(ApplicationProvider.getApplicationContext<Context>())
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val day = LocalDate.of(2026, 10, 17)
            val active = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id, priceYuan = "50"))
            val cancelledShort = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id).copy(paymentMode = PaymentMode.INSTALLMENTS))
            val cancelledHosting = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day, day.plusDays(5)).copy(paymentMode = PaymentMode.INSTALLMENTS))
            val periodId = env.orderRepository.observeOrderDetail(cancelledHosting).first()!!.periods.single().id
            listOf(cancelledShort to null, cancelledHosting to periodId).forEachIndexed { index, (id, period) ->
                env.receiptRepository.recordPayment(PaymentRequest(id, period, 1000, env.clock.now(), PaymentChannel.WECHAT, null, "cancelled-delete-payment-$index"))
                env.orderRepository.cancelOrder(id)
            }
            val preview = env.accountRepository.deletionImpact(account.id)
            assertEquals(5000L, preview.outstandingCents)
            assertEquals(2000L, preview.receiptSumCents)
            assertEquals(2, preview.receiptCount)
            assertEquals(3, preview.orderCount)
            assertEquals(1, preview.activeOrderCount)
            assertEquals(1, preview.periodCount)
            assertNotNull(env.database.orderDao().findById(active))
            assertEquals(3, env.database.orderDao().findForAccount(account.id).size)
        } finally { env.close() }
    }
}
