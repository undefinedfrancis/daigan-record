package com.daigan.record.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.local.RECORD_DATABASE_VERSION
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.entity.ReceiptEntity
import com.daigan.record.data.model.*
import com.daigan.record.testing.*
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real repositories, ZIP export/read, isolated production-schema validation and replaceAll. */
@RunWith(AndroidJUnit4::class)
class FinancialBackupCompatibilityTest {
    private class PrivateContext(base: Context, val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    }
    private fun context(): PrivateContext {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return PrivateContext(base, File(base.cacheDir, "financial-backup-${UUID.randomUUID()}").apply { mkdirs() })
    }
    private suspend fun validateExport(context: Context, source: OrderTestEnvironment): BackupDataSet {
        val access = BackupDatabaseAccess(context, source.database, source.clock)
        val storage = BackupStorage(context, source.clock)
        val built = BackupSnapshotBuilder(storage, access).build(storage.newWorkDirectory("export"), source.clock.now(), null)
        val extracted = BackupArchiveReader.read(built.file, storage.newWorkDirectory("import"), RECORD_DATABASE_VERSION)
        val data = BackupDataCodec.decode(extracted.dataBytes.toString(Charsets.UTF_8))
        val staging = Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java).build()
        try {
            access.insertAll(staging.openHelper.writableDatabase, data)
            BackupValidator().validate(staging, data, extracted)
        } finally { staging.close() }
        return data
    }
    @Test fun zeroPricesAndReceiptsAboveCorrectedPricesRoundTripWithoutLosingMoney() = runBlocking {
        val context = context()
        val source = OrderTestEnvironment(context)
        val target = OrderTestEnvironment(context)
        try {
            val (owner, account) = source.seedOwnerAndAccount()
            val day = LocalDate.of(2026, 10, 17)
            val shortZero = source.orderRepository.createShortOrder(shortDraft(owner.id, account.id, priceYuan = "0"))
            val hostingZero = source.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day, day.plusDays(3), priceYuan = "0"))
            val shortPaid = source.orderRepository.createShortOrder(shortDraft(owner.id, account.id).copy(paymentMode = PaymentMode.INSTALLMENTS))
            val hostingPaid = source.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day, day.plusDays(5)).copy(paymentMode = PaymentMode.INSTALLMENTS))
            val period = source.orderRepository.observeOrderDetail(hostingPaid).first()!!.periods.single()
            listOf(shortPaid to null, hostingPaid to period.id).forEachIndexed { index, (id, periodId) ->
                source.receiptRepository.recordPayment(PaymentRequest(id, periodId, 4000, source.clock.now(), PaymentChannel.WECHAT, null, "paid-$index"))
            }
            source.orderRepository.updateShortOrderPrice(shortPaid, 0, "short-price-zero", "免单，保留实际到账")
            source.orderRepository.updatePeriodPrice(hostingPaid, period.id, 1000, "hosting-price-reduced", "更正价格")
            val before = BackupDatabaseAccess(context, source.database, source.clock).dump()
            val data = validateExport(context, source)
            BackupDatabaseAccess(context, target.database, target.clock).replaceAll(data, "financial-roundtrip")
            val after = BackupDatabaseAccess(context, target.database, target.clock).dump()
            assertEquals(before.tables, after.tables)
            assertEquals(0L, target.database.orderDao().findById(shortZero)!!.priceCents)
            assertEquals(0L, target.database.orderDao().findPeriods(hostingZero).single().priceCents)
            assertEquals(8000L, target.database.receiptDao().findAll().sumOf { it.amountCents })
            assertEquals(0L, target.orderRepository.observeOrderDetail(shortPaid).first()!!.balance().totalOutstandingCents)
            assertEquals(0L, target.orderRepository.observeOrderDetail(hostingPaid).first()!!.balance().totalOutstandingCents)
        } finally { source.close(); target.close(); context.root.deleteRecursively() }
    }
    @Test fun convertedOrderTypesRestoreOriginalCreationIdentityAndRemainIdempotent() = runBlocking {
        val context = context()
        val source = OrderTestEnvironment(context)
        val target = OrderTestEnvironment(context)
        try {
            val (owner, account) = source.seedOwnerAndAccount()
            val day = LocalDate.of(2026, 10, 17)
            val originalShort = shortDraft(owner.id, account.id)
            val originalHosting = hostingDraft(owner.id, account.id, day, day.plusDays(4))
            val shortId = source.orderRepository.createShortOrder(originalShort)
            val hostingId = source.orderRepository.createHostingOrder(originalHosting)
            source.orderRepository.updateUnusedOrderIdentity(shortId, account.id, OrderType.HOSTING, day, day.plusDays(5))
            source.orderRepository.updateUnusedOrderIdentity(hostingId, account.id, OrderType.SHORT_TASK, day, null)
            val data = validateExport(context, source)
            BackupDatabaseAccess(context, target.database, target.clock).replaceAll(data, "converted-roundtrip")
            assertEquals(OrderType.HOSTING, target.database.orderDao().findById(shortId)!!.type)
            assertEquals(OrderType.SHORT_TASK, target.database.orderDao().findById(hostingId)!!.type)
            // The original request type and fingerprint remain an idempotency record,
            // rather than being rewritten to pretend the original request was different.
            assertEquals(shortId, target.orderRepository.createShortOrder(originalShort))
            assertEquals(hostingId, target.orderRepository.createHostingOrder(originalHosting))
            assertEquals(2, target.database.orderDao().findAllOrders().size)
        } finally { source.close(); target.close(); context.root.deleteRecursively() }
    }
    @Test fun nonPositiveReceiptStillRejectedByRealBackupValidation() = runBlocking {
        assertRejectedReceipt(0, null, "收款金额无效")
        assertRejectedReceipt(-1, null, "收款金额无效")
    }
    @Test fun receiptLinkedToAnotherOrdersPeriodStillRejected() = runBlocking {
        val context = context()
        val env = OrderTestEnvironment(context)
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val day = LocalDate.of(2026, 10, 17)
            val a = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day, day.plusDays(2)))
            val b = env.orderRepository.createHostingOrder(hostingDraft(owner.id, account.id, day, day.plusDays(2)))
            val wrongPeriod = env.database.orderDao().findPeriods(b).single().id
            env.database.receiptDao().insert(ReceiptEntity("wrong", "wrong-op", a, wrongPeriod, 100, env.clock.now(), PaymentChannel.WECHAT, null, ReceiptStatus.ACTIVE, null, env.clock.now()))
            val failure = runCatching { validateExport(context, env) }.exceptionOrNull()
            assertTrue(failure is BackupException)
            assertTrue(failure!!.message.orEmpty().contains("归属"))
        } finally { env.close(); context.root.deleteRecursively() }
    }
    @Test fun receiptAggregationStillRejectsLongOverflow() = runBlocking {
        assertRejectedReceipt(Long.MAX_VALUE, 1, "收款总额超出范围")
    }
    private suspend fun assertRejectedReceipt(amount: Long, second: Long?, expected: String) {
        val context = context()
        val env = OrderTestEnvironment(context)
        try {
            val (owner, account) = env.seedOwnerAndAccount()
            val id = env.orderRepository.createShortOrder(shortDraft(owner.id, account.id))
            listOfNotNull(amount, second).forEachIndexed { index, cents ->
                env.database.receiptDao().insert(ReceiptEntity("bad-$index", "bad-op-$index", id, null, cents, env.clock.now(), PaymentChannel.WECHAT, null, ReceiptStatus.ACTIVE, null, env.clock.now()))
            }
            val failure = runCatching { validateExport(context, env) }.exceptionOrNull()
            assertTrue(failure is BackupException)
            assertTrue(failure!!.message.orEmpty().contains(expected))
        } finally { env.close(); context.root.deleteRecursively() }
    }
}
