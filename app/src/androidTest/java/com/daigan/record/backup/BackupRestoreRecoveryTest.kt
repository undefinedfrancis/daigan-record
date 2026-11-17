package com.daigan.record.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daigan.record.core.time.ReportingTimeZoneProvider
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RECORD_DATABASE_VERSION
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.local.entity.*
import com.daigan.record.data.model.*
import java.io.FileOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import com.daigan.record.data.repository.DisplayedHostingSnapshot
import com.daigan.record.data.repository.AttachmentSync
import com.daigan.record.data.repository.DatasetGate
import com.daigan.record.data.repository.ApplicationReadiness
import com.daigan.record.notifications.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room, private files, real ZIP/crypto and real journal; only document/scheduling I/O is fake. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreRecoveryTest {
    private val checkpoints = listOf("PREPARED", "ROLLBACK_READY", "ORIGINAL_FILES_MOVED",
        "FILES_SWAPPED", "DATABASE_COMMITTED", "DB_COMMITTED")

    private class PrivateContext(base: Context, val directory: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(directory, "databases/$name").apply {
            parentFile!!.mkdirs()
        }
        override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()
    }

    private class Fixture(val context: PrivateContext) : AutoCloseable {
        val clock = TimeProvider { Instant.ofEpochMilli(1000) }
        val db = Room.databaseBuilder(context, RecordDatabase::class.java, "live.db").build()
        val storage = BackupStorage(context, clock)
        val access = BackupDatabaseAccess(context, db, clock)
        val builder = BackupSnapshotBuilder(storage, access)
        val sync = AttachmentSync()
        val document = object : BackupDocumentStore {
            override suspend fun copyDocumentToFile(uri: Uri, target: File, maxBytes: Long): Long {
                val file = File(checkNotNull(uri.path))
                check(file.length() <= maxBytes)
                file.copyTo(target, overwrite = true)
                return file.length()
            }
            override suspend fun writeFileToDocument(uri: Uri, source: File) {
                source.copyTo(File(checkNotNull(uri.path)), overwrite = true)
            }
            override fun hasRoomFor(bytes: Long) = true
        }
        var cancelledSchedules = 0
        val scheduledSettings = mutableListOf<ReminderSettings>()
        var failScheduling = false
        private val scheduler = object : ReminderScheduler {
            override suspend fun scheduleAll(settings: ReminderSettings, now: Instant) {
                if (failScheduling) throw java.io.IOException("Injected scheduler failure")
                scheduledSettings += settings
            }
            override suspend fun scheduleCategory(category: ReminderCategory, minutes: Int, now: Instant) = Unit
            override suspend fun cancel(category: ReminderCategory) = Unit
            override suspend fun cancelAll() { cancelledSchedules++ }
        }
        private val notifier = object : ReminderNotifier {
            override fun canNotify() = false
            override fun post(message: ReminderMessage) = false
            override fun notificationSettingsIntent() = Intent()
        }
        val coordinator = ReminderCoordinator(DefaultReminderSettingsRepository(db.reminderSettingsDao(), clock),
            ReminderRepository(db, db.orderDao(), db.gameAccountDao(), db.ownerDao()), scheduler,
            notifier, clock, ReportingTimeZoneProvider { ZoneId.of("Asia/Shanghai") })
        fun service() = BackupRestoreService(storage, access, builder, BackupValidator(), sync,
            document, clock, coordinator)

        fun repository(): com.daigan.record.data.repository.DefaultOrderRepository {
            val workClock = TimeProvider { Instant.parse("2026-10-10T05:00:00Z") }
            val countSessions = com.daigan.record.data.repository.DefaultCountSessionService(
                db = db,
                orderDao = db.orderDao(),
                taskDao = db.taskDao(),
                occurrenceDao = db.occurrenceDao(),
                eventDao = db.taskProgressEventDao(),
                sessionDao = db.hostingSessionDao(),
                accountDao = db.gameAccountDao(),
                ownerDao = db.ownerDao(),
                timeProvider = workClock,
            )
            return com.daigan.record.data.repository.DefaultOrderRepository(
                database = db, orderDao = db.orderDao(), taskDao = db.taskDao(),
                occurrenceDao = db.occurrenceDao(), batchDao = db.completionBatchDao(),
                attachmentDao = db.attachmentDao(), accountDao = db.gameAccountDao(),
                ownerDao = db.ownerDao(), receiptDao = db.receiptDao(),
                receiptLedger = com.daigan.record.data.repository.ReceiptLedger(db.receiptDao(), db.orderDao(), workClock),
                creationOperationDao = db.creationOperationDao(),
                hostingSessionDao = db.hostingSessionDao(),
                eventDao = db.taskProgressEventDao(),
                countSessions = countSessions,
                ticker = com.daigan.record.core.time.TimeTicker(workClock,
                    com.daigan.record.core.time.RealBoundaryWaiter()),
            )
        }

        suspend fun seed(name: String) {
            access.deleteAll(db.openHelper.writableDatabase)
            db.ownerDao().insert(OwnerEntity(name, 1, name, clock.now(), clock.now(), null))
            db.openHelper.writableDatabase.execSQL("INSERT INTO owner_number_sequence VALUES (0, 2)")
            db.reminderSettingsDao().upsert(ReminderSettingsEntity(0, name == "new", 123,
                false, 456, false, 789, clock.now()))
            if (name == "new") {
                db.gameAccountDao().insert(GameAccountEntity("account", name, "游戏", "官服", "uid",
                    "login", LoginMethod.PASSWORD, "private-secret", clock.now(), clock.now(), null))
                db.orderDao().insert(OrderEntity("order", "account", OrderType.SHORT_TASK, "截图任务", 1000,
                    PaymentMode.INSTALLMENTS, LocalDate.of(2026, 10, 20), null, "Asia/Shanghai", 240,
                    1, 240, clock.now(), clock.now(), null,
                    // UF-028: order colour is business data and must survive export/restore.
                    orderColor = 0xFF2E7D32.toInt()))
                db.taskDao().insert(TaskDefinitionEntity("task", "order", null, "截图", ProgressType.CHECKBOX,
                    BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, null, RecurrenceType.ONE_TIME,
                    null, null, 0, null, null, clock.now(), clock.now()))
                storage.attachmentsDirectory.mkdirs()
                val image = File(storage.attachmentsDirectory, "new-image.jpg")
                image.writeBytes(byteArrayOf(1, 3, 5, 7, 9))
                db.attachmentDao().insert(AttachmentEntity("image", "task", null, image.name,
                    "image/jpeg", image.length(), clock.now()))
            }
        }

        suspend fun richHistory() {
            val day = LocalDate.of(2026, 10, 1)
            val now = clock.now()
            db.templateDao().insertTemplate(GameTemplateEntity("template", "自定义游戏", "v2", "已更新",
                "Asia/Shanghai", 300, 1, 300, TemplateOrigin.USER, null, 0, now, now,
                // UF-034: a count-based template must keep its mode and target through backup.
                hostingMode = HostingMode.COUNT_BASED, targetSessions = 6))
            db.serverOptionDao().insertAll(listOf(ServerOptionEntity("server-option", "自定义游戏", "官服", 0)))
            db.templateDao().insertPricePresets(listOf(TemplatePricePresetEntity("price-preset", "template", 1000, "月托", 0)))
            db.templateDao().insertCategories(listOf(TemplateCategoryEntity("category", "template", "日常", 0)))
            db.templateDao().insertTasks(listOf(TemplateTaskEntity("template-task", "template", "category", "新版任务",
                ProgressType.CHECKBOX, BigDecimal.ZERO, BigDecimal.ONE, null, RecurrenceType.DAILY, null, null, 0)))
            db.templateDao().insertSeedState(TemplateSeedStateEntity("deleted-built-in", now))
            db.orderDao().insert(OrderEntity("hosting", "account", OrderType.HOSTING, "历史托管", null,
                PaymentMode.INSTALLMENTS, null, "保留旧版快照", "Asia/Shanghai", 240, 1, 240,
                now, now, null, "template", "旧版名称", "v1"))
            db.orderDao().insertPeriod(HostingPeriodEntity("period-old", "hosting", 1, day, day.plusDays(6),
                1000, "Asia/Shanghai", 240, 1, 240, now, null))
            db.orderDao().insertPeriod(HostingPeriodEntity("period-new", "hosting", 2, day.plusDays(7), day.plusDays(13),
                2000, "Asia/Shanghai", 240, 1, 240, now, "renew-once"))
            db.taskDao().insert(TaskDefinitionEntity("hosting-task-old", "hosting", "period-old", "旧任务快照",
                ProgressType.CHECKBOX, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, null,
                RecurrenceType.DAILY, null, null, 0, null, null, now, now))
            db.taskDao().insert(TaskDefinitionEntity("hosting-task-new", "hosting", "period-new", "续期任务快照",
                ProgressType.NUMERIC, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO, "次",
                RecurrenceType.EVERY_N_DAYS, null, 2, 0, null, null, now, now))
            db.completionBatchDao().insertBatch(CompletionBatchEntity("batch", "batch-operation", BatchScope.HOSTING_ORDER,
                "hosting", day, now, now.plusSeconds(5)))
            db.occurrenceDao().insertIgnore(TaskOccurrenceEntity("occurrence", "hosting-task-old", "D:$day", day,
                day, day, now, now.plusSeconds(60), true, null, null, now))
            db.completionBatchDao().insertItems(listOf(CompletionBatchItemEntity("batch-item", "batch",
                BatchItemType.OCCURRENCE, "occurrence", "hosting-task-old", null, null, false, null, null,
                now, now, false, null, "batch")))
            db.receiptDao().insert(ReceiptEntity("short-receipt", "short-payment", "order", null, 123, now,
                PaymentChannel.WECHAT, null, ReceiptStatus.ACTIVE, null, now))
            db.receiptDao().insert(ReceiptEntity("old-receipt", "old-payment", "hosting", "period-old", 250, now,
                PaymentChannel.ALIPAY, "被更正", ReceiptStatus.REPLACED, "corrected-receipt", now))
            db.receiptDao().insert(ReceiptEntity("corrected-receipt", "correct-payment", "hosting", "period-old", 300,
                now.plusSeconds(1), PaymentChannel.OTHER, "历史欠款分期", ReceiptStatus.ACTIVE, null, now))
            db.receiptDao().insert(ReceiptEntity("period-payment", "new-period-payment", "hosting", "period-new", 500,
                now.plusSeconds(2), PaymentChannel.WECHAT, "本期分期", ReceiptStatus.ACTIVE, null, now))
            db.receiptDao().insertCorrection(ReceiptCorrectionEntity("correction", "correction-operation", "old-receipt",
                ReceiptCorrectionKind.AMEND, "修正金额", 250, now, PaymentChannel.ALIPAY, "被更正",
                "corrected-receipt", 300, now.plusSeconds(1), PaymentChannel.OTHER, "历史欠款分期", now))
            db.orderDao().insertPriceChange(OrderPriceChangeEntity("price-change", "price-change-op", "order", null, 900, 1000, "更正约定价格", now))
            db.creationOperationDao().insert(OrderCreationOperationEntity("create-order", OrderType.SHORT_TASK,
                "persisted-fingerprint", "order", "short-receipt", now))
            // 分类与累计事件也必须进入往返夹具：空表会让"覆盖全部表"的断言失去意义。
            db.accountCategoryDao().insert(AccountCategoryEntity("category-account", "日常", 0, now, now))
            // 短期单的累计任务：事件总值低于目标，因此恢复期的完成投影不改变派生值。
            db.taskDao().insert(TaskDefinitionEntity("short-cumulative", "order", null, "累计任务",
                ProgressType.NUMERIC, BigDecimal.ZERO, BigDecimal.valueOf(20), BigDecimal.valueOf(5),
                "次", RecurrenceType.DAILY, null, null, 1, null, null, now, now,
                numericMode = NumericMode.CUMULATIVE))
            db.taskProgressEventDao().insert(TaskProgressEventEntity("progress-event", "short-cumulative",
                "progress-operation", ProgressEventKind.SUBMIT, ProgressScopeType.TASK, null, null,
                day, BigDecimal.valueOf(5), 1, null, now, now))

            // UF-033: a real count-based order with its container period, two tasks, one
            // completed round and one unfinished round that already has progress. Rounds
            // and their N:<index> occurrences must survive export/restore exactly.
            db.orderDao().insert(OrderEntity("count-hosting", "account", OrderType.HOSTING, "打卡式托管",
                null, PaymentMode.INSTALLMENTS, null, "按轮次结算", "Asia/Shanghai", 240, 1, 240,
                now, now, null, null, null, null, null, null, HostingMode.COUNT_BASED, 4))
            // UF-033: a count period carries its own target; the round rows below prove it.
            db.orderDao().insertPeriod(HostingPeriodEntity("count-period", "count-hosting", 0, day, day,
                1200, "Asia/Shanghai", 240, 1, 240, now, null, serviceCount = 4))
            db.taskDao().insert(TaskDefinitionEntity("count-task-a", "count-hosting", "count-period", "签到",
                ProgressType.CHECKBOX, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, null,
                RecurrenceType.DAILY, null, null, 0, null, null, now, now))
            db.taskDao().insert(TaskDefinitionEntity("count-task-b", "count-hosting", "count-period", "副本",
                ProgressType.NUMERIC, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO, "次",
                RecurrenceType.DAILY, null, null, 1, null, null, now, now))
            db.hostingSessionDao().insertIgnore(
                HostingSessionEntity("count-session-1", "count-hosting", 1, now,
                    now.plusSeconds(10), periodId = "count-period"),
            )
            db.hostingSessionDao().insertIgnore(
                HostingSessionEntity("count-session-2", "count-hosting", 2, now.plusSeconds(20),
                    null, periodId = "count-period"),
            )
            db.occurrenceDao().insertIgnore(TaskOccurrenceEntity("count-occ-1a", "count-task-a", "N:1", day,
                null, null, now.plusSeconds(10), now.plusSeconds(10), false, null, BigDecimal.ONE, now))
            db.occurrenceDao().insertIgnore(TaskOccurrenceEntity("count-occ-1b", "count-task-b", "N:1", day,
                null, null, now.plusSeconds(10), now.plusSeconds(10), false, null, BigDecimal.TEN, now))
            // Round 2 is unfinished but task A is already checked off.
            db.occurrenceDao().insertIgnore(TaskOccurrenceEntity("count-occ-2a", "count-task-a", "N:2", day,
                null, null, now.plusSeconds(30), now.plusSeconds(30), false, null, BigDecimal.ONE, now))
        }

        suspend fun prepare(service: BackupRestoreService) {
            seed("new")
            val export = storage.newWorkDirectory("fixture-export")
            val snapshot = builder.build(export, clock.now(), null)
            val archive = File(context.directory, "incoming.dgbk")
            snapshot.file.copyTo(archive, overwrite = true)
            seed("original")
            storage.attachmentsDirectory.deleteRecursively()
            storage.attachmentsDirectory.mkdirs()
            File(storage.attachmentsDirectory, "original-proof.jpg").writeText("original")
            service.inspect(Uri.fromFile(archive))
            val summary = service.prepare(null)
            assertEquals(1, summary.owners)
        }

        suspend fun assertDataset(expected: String) {
            assertEquals(listOf(expected), db.ownerDao().findAll().map { it.id })
            assertEquals(expected == "original", File(storage.attachmentsDirectory, "original-proof.jpg").exists())
            assertEquals(expected == "new", db.reminderSettingsDao().find()!!.shortDeadlineEnabled)
            if (expected == "new") {
                assertEquals("private-secret", db.gameAccountDao().findAll().single().password)
                assertEquals("image", db.orderDao().findAllAttachments().single().id)
                assertArrayEquals(byteArrayOf(1, 3, 5, 7, 9),
                    File(storage.attachmentsDirectory, "new-image.jpg").readBytes())
            }
        }
        override fun close() = db.close()
    }

    private fun context(name: String) = PrivateContext(
        InstrumentationRegistry.getInstrumentation().targetContext,
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "backup-tests/$name"),
    )

    @Test fun realBulkCompletionCanBeRestoredAndUndone() = runBlocking<Unit> {
        listOf(false, true).forEach { encrypted ->
            val sourceContext = context("bulk-source-$encrypted")
            val targetContext = context("bulk-target-$encrypted")
            sourceContext.directory.deleteRecursively(); targetContext.directory.deleteRecursively()
            try {
                val archive = File(sourceContext.directory, "bulk.dgbk")
                lateinit var batchId: String
                Fixture(sourceContext).use { source ->
                    source.seed("new"); source.richHistory()
                    val repo = source.repository()
                    val shown = repo.observeTodayHosting().first().first { it.order.id == "hosting" }
                    val result = repo.completeHostingOrder("hosting", "bulk-roundtrip",
                        DisplayedHostingSnapshot(shown.order.id, shown.period.id, shown.today,
                            shown.tasks.associate { it.task.id to it.slot.key }))
                    assertTrue(result.completedCount > 0)
                    batchId = result.batchId
                    val items = source.db.completionBatchDao().findItems(batchId)
                    assertTrue(items.all { it.occurrenceId != null && it.taskDefinitionId != null })
                    val snapshot = source.builder.build(source.storage.newWorkDirectory("bulk-export"),
                        source.clock.now(), if (encrypted) "password".toCharArray() else null)
                    snapshot.file.copyTo(archive, overwrite = true)
                }
                Fixture(targetContext).use { target ->
                    val service = target.service()
                    service.inspect(Uri.fromFile(archive))
                    service.prepare(if (encrypted) "password".toCharArray() else null)
                    service.applyConfirmed()
                    val items = target.db.completionBatchDao().findItems(batchId)
                    assertTrue(items.isNotEmpty())
                    val undone = target.repository().undoBatch(batchId)
                    assertEquals(items.size, undone.restoredCount)
                    assertTrue(undone.conflicts.isEmpty())
                    items.forEach {
                        assertNull(target.db.occurrenceDao().findById(checkNotNull(it.occurrenceId))!!.completedAt)
                    }
                }
            } finally { sourceContext.directory.deleteRecursively(); targetContext.directory.deleteRecursively() }
        }
    }

    @Test fun catalogCoversEveryProductionTableAndColumn() = runBlocking<Unit> {
        val context = context("catalog")
        context.directory.deleteRecursively()
        try {
            Fixture(context).use { fixture ->
                val raw = fixture.db.openHelper.writableDatabase
                val actual = mutableSetOf<String>()
                raw.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        if (!name.startsWith("sqlite_") && name !in setOf("room_master_table", "android_metadata")) actual += name
                    }
                }
                assertEquals(BackupTableCatalog.tables.map { it.name }.toSet(), actual)
                BackupTableCatalog.tables.forEach { table ->
                    val columns = mutableListOf<String>()
                    raw.query("PRAGMA table_info(`${table.name}`)").use { cursor ->
                        while (cursor.moveToNext()) columns += cursor.getString(1)
                    }
                    // 真实 Room schema == 写出的列集；legacy-only 列只存在于历史文件，
                    // 绝不能被当成"真实列"（否则 schema 15 的退役列会被误判为缺失）。
                    assertEquals(table.name, table.writtenColumnNames, columns)
                    assertEquals(emptyList<String>(), table.columnNames.filter { it !in columns && table.column(it)?.legacyOnly != true })
                }
            }
        } finally { context.directory.deleteRecursively() }
    }

    @Test fun plainAndEncryptedArchivesRestoreCredentialsScreenshotsAndSettingsIntoFreshDatabase() = runBlocking<Unit> {
        listOf(false, true).forEach { encrypted ->
            val sourceContext = context("roundtrip-source-$encrypted")
            val targetContext = context("roundtrip-target-$encrypted")
            sourceContext.directory.deleteRecursively(); targetContext.directory.deleteRecursively()
            try {
                val archive = File(sourceContext.directory, "export.dgbk")
                var expected: Map<String, List<String>> = emptyMap()
                Fixture(sourceContext).use { source ->
                    source.seed("new")
                    source.richHistory()
                    expected = source.access.dump().tables.associate { it.table.name to it.rows.map { row -> row.toString() }.sorted() }
                    assertTrue("Round-trip fixture must cover all included tables; empty: ${expected.filterValues { it.isEmpty() }.keys}", expected.values.all { it.isNotEmpty() })
                    val snapshot = source.builder.build(source.storage.newWorkDirectory("export"),
                        source.clock.now(), if (encrypted) "恢复 password".toCharArray() else null)
                    snapshot.file.copyTo(archive, overwrite = true)
                }
                Fixture(targetContext).use { target ->
                    val service = target.service()
                    assertEquals(encrypted, service.inspect(Uri.fromFile(archive)).encrypted)
                    val preview = service.prepare(if (encrypted) "恢复 password".toCharArray() else null)
                    assertEquals(1, preview.attachments)
                    assertEquals(1, preview.remindersEnabled)
                    service.applyConfirmed()
                    target.assertDataset("new")
                    assertEquals(expected, target.access.dump().tables.associate {
                        it.table.name to it.rows.map { row -> row.toString() }.sorted()
                    })
                    assertEquals(3, target.db.orderDao().findAllPeriods().size)
                    assertEquals("v1", target.db.orderDao().findAllOrders().first { it.id == "hosting" }.sourceTemplateVersion)
                    // UF-028: a chosen colour survives, and an uncoloured order is not given one.
                    assertEquals(0xFF2E7D32.toInt(), target.db.orderDao().findAllOrders().first { it.id == "order" }.orderColor)
                    assertNull(target.db.orderDao().findAllOrders().first { it.id == "hosting" }.orderColor)
                    // UF-034: template count-hosting defaults survive the same round trip.
                    val template = target.db.templateDao().findAllTemplates().first { it.id == "template" }
                    assertEquals(HostingMode.COUNT_BASED, template.hostingMode)
                    assertEquals(6, template.targetSessions)
                    val countOrder = target.db.orderDao().findAllOrders().first { it.id == "count-hosting" }
                    assertEquals(HostingMode.COUNT_BASED, countOrder.hostingMode)
                    assertEquals(4, countOrder.targetSessions)
                    val sessions = target.db.hostingSessionDao().findForOrder("count-hosting")
                        .associateBy { it.sessionIndex }
                    assertEquals(setOf(1, 2), sessions.keys)
                    assertNotNull(sessions.getValue(1).completedAt)
                    assertNull(sessions.getValue(2).completedAt)
                    // Round 2's partial progress survives with its own N:2 identity.
                    val roundTwo = target.db.occurrenceDao().findForOrder("count-hosting")
                        .filter { it.occurrenceKey == "N:2" }
                    assertEquals(listOf("count-task-a"), roundTwo.filter { it.completedAt != null }.map { it.taskDefinitionId })
                    assertEquals(2, target.db.occurrenceDao().findForOrder("count-hosting")
                        .count { it.occurrenceKey == "N:1" })
                }
                Fixture(targetContext).use { target -> target.service().recoverIfNeeded(); target.assertDataset("new") }
            } finally { sourceContext.directory.deleteRecursively(); targetContext.directory.deleteRecursively() }
        }
    }

    /**
     * UF-017 through the real archive path: export, validation against a staging database
     * and the destructive replace, all driven by the production catalog.
     */
    @Test fun accountResourceTextSurvivesARealArchiveRoundTrip() = runBlocking<Unit> {
        val sourceContext = context("resource-archive-source")
        val targetContext = context("resource-archive-target")
        sourceContext.directory.deleteRecursively(); targetContext.directory.deleteRecursively()
        try {
            val archive = File(sourceContext.directory, "resource.dgbk")
            Fixture(sourceContext).use { source ->
                source.seed("new")
                source.db.openHelper.writableDatabase.execSQL(
                    "UPDATE game_accounts SET resourceNotes = '3 星专武 · 60 抽', loginDevice = '平板'",
                )
                val snapshot = source.builder.build(
                    source.storage.newWorkDirectory("resource-export"),
                    source.clock.now(),
                    null,
                )
                snapshot.file.copyTo(archive, overwrite = true)
            }
            Fixture(targetContext).use { target ->
                val service = target.service()
                service.inspect(Uri.fromFile(archive))
                // prepare() validates the payload in an isolated staging database.
                service.prepare(null)
                service.applyConfirmed()
                val account = target.db.gameAccountDao().findAll().single()
                assertEquals("3 星专武 · 60 抽", account.resourceNotes)
                assertEquals("平板", account.loginDevice)
                assertEquals("private-secret", account.password)
                assertEquals("image", target.db.orderDao().findAllAttachments().single().id)
            }
        } finally {
            sourceContext.directory.deleteRecursively(); targetContext.directory.deleteRecursively()
        }
    }

    @Test fun everyRestoreFailureLeavesOneCompleteDatasetAndRecoveryIsIdempotent() = runBlocking<Unit> {
        checkpoints.forEach { phase ->
            val context = context("failure-$phase")
            context.directory.deleteRecursively()
            try {
                Fixture(context).use { fixture ->
                    val service = fixture.service()
                    fixture.prepare(service)
                    service.transitionHook = { if (it == phase) throw java.io.IOException("injected") }
                    try { service.applyConfirmed(); fail("Failure hook not reached at $phase") }
                    catch (_: java.io.IOException) { }
                    val expected = if (phase in listOf("DATABASE_COMMITTED", "DB_COMMITTED")) "new" else "original"
                    fixture.assertDataset(expected)
                }
                Fixture(context).use { reopened ->
                    reopened.service().recoverIfNeeded()
                    reopened.service().recoverIfNeeded()
                    reopened.assertDataset(if (phase in listOf("DATABASE_COMMITTED", "DB_COMMITTED")) "new" else "original")
                }
            } finally { context.directory.deleteRecursively() }
        }
    }

    @Test fun cancellationAtDatabaseCommitStillRebuildsRemindersFromRestoredPreferences() = runBlocking<Unit> {
        val context = context("cancel-after-commit")
        context.directory.deleteRecursively()
        try {
            Fixture(context).use { fixture ->
                val service = fixture.service()
                fixture.prepare(service)
                val restore = launch(start = CoroutineStart.LAZY) { service.applyConfirmed() }
                service.transitionHook = { if (it == "DATABASE_COMMITTED") restore.cancel() }
                restore.start()
                restore.join()
                assertTrue(restore.isCancelled)
                fixture.assertDataset("new")
                assertEquals(1, fixture.cancelledSchedules)
                assertEquals(1, fixture.scheduledSettings.size)
                assertTrue(fixture.scheduledSettings.single().shortDeadlineEnabled)
                assertEquals(123, fixture.scheduledSettings.single().shortDeadlineMinutes)
                assertFalse(fixture.storage.hasJournalFile())
                assertNull(fixture.access.currentRestoreMarker())
            }
        } finally { context.directory.deleteRecursively() }
    }

    @Test fun failedReminderReconciliationRetainsCommitEvidenceUntilSuccessfulRecovery() = runBlocking<Unit> {
        val context = context("schedule-retry")
        context.directory.deleteRecursively()
        try {
            Fixture(context).use { fixture ->
                val service = fixture.service()
                fixture.prepare(service)
                fixture.failScheduling = true
                try { service.applyConfirmed(); fail("Expected scheduler failure") }
                catch (_: BackupException) { }
                fixture.assertDataset("new")
                assertTrue(fixture.storage.hasJournalFile())
                assertNotNull(fixture.access.currentRestoreMarker())
                // Startup recovery also must retain the marker while scheduling fails.
                try { fixture.service().recoverIfNeeded(); fail("Expected recovery scheduler failure") }
                catch (_: java.io.IOException) { }
                assertTrue(fixture.storage.hasJournalFile())
                assertNotNull(fixture.access.currentRestoreMarker())
            }
            Fixture(context).use { reopened ->
                assertEquals(RecoveryOutcome.COMPLETED_RESTORE, reopened.service().recoverIfNeeded())
                reopened.assertDataset("new")
                assertEquals(1, reopened.cancelledSchedules)
                assertTrue(reopened.scheduledSettings.single().shortDeadlineEnabled)
                assertEquals(123, reopened.scheduledSettings.single().shortDeadlineMinutes)
                assertFalse(reopened.storage.hasJournalFile())
                assertNull(reopened.access.currentRestoreMarker())
            }
        } finally {
            context.directory.deleteRecursively()
            DatasetGate.recoveryFinished()
            ApplicationReadiness.ready()
        }
    }

    @Test fun invalidCounterAndCrossOrderReceiptLinksFailBeforeReplacement() = runBlocking<Unit> {
        listOf("counter", "replacement", "correction", "replacement-period", "correction-period").forEach { invalid ->
            val sourceContext = context("invalid-source-$invalid")
            val targetContext = context("invalid-target-$invalid")
            sourceContext.directory.deleteRecursively(); targetContext.directory.deleteRecursively()
            try {
                val archive = File(sourceContext.directory, "invalid.dgbk")
                Fixture(sourceContext).use { source ->
                    if (invalid == "counter") {
                        source.db.openHelper.writableDatabase.execSQL("INSERT INTO owner_number_sequence VALUES (0, -1)")
                    } else {
                        source.seed("new"); source.richHistory()
                        val targetId = if (invalid.endsWith("-period")) "period-payment" else "short-receipt"
                        source.db.openHelper.writableDatabase.execSQL(if (invalid.startsWith("replacement"))
                            "UPDATE receipts SET replacedByReceiptId='$targetId' WHERE id='old-receipt'"
                            else "UPDATE receipt_corrections SET newReceiptId='$targetId' WHERE id='correction'")
                    }
                    source.builder.build(source.storage.newWorkDirectory("invalid"), source.clock.now(), null)
                        .file.copyTo(archive, overwrite = true)
                }
                Fixture(targetContext).use { target ->
                    target.seed("original")
                    target.storage.attachmentsDirectory.mkdirs()
                    File(target.storage.attachmentsDirectory, "original-proof.jpg").writeText("original")
                    val service = target.service()
                    service.inspect(Uri.fromFile(archive))
                    try { service.prepare(null); fail("Invalid $invalid imported") }
                    catch (_: BackupException) { }
                    target.assertDataset("original")
                    assertFalse(target.storage.hasJournalFile())
                }
            } finally { sourceContext.directory.deleteRecursively(); targetContext.directory.deleteRecursively() }
        }
    }

    @Test fun emptyOriginalAttachmentDirectoryIsRestoredOnSwapFailure() = runBlocking<Unit> {
        val context = context("empty-original")
        context.directory.deleteRecursively()
        try {
            Fixture(context).use { fixture ->
                val service = fixture.service()
                fixture.prepare(service)
                fixture.storage.attachmentsDirectory.deleteRecursively()
                service.transitionHook = { if (it == "FILES_SWAPPED") throw java.io.IOException("injected") }
                try { service.applyConfirmed(); fail("Expected failure") } catch (_: java.io.IOException) { }
                assertEquals("original", fixture.db.ownerDao().findAll().single().id)
                assertTrue(fixture.storage.attachmentsDirectory.isDirectory)
                assertFalse(fixture.storage.hasJournalFile())
            }
        } finally { context.directory.deleteRecursively() }
    }

    @Test fun corruptJournalBlocksAccessAndPreservesEvidence() = runBlocking<Unit> {
        val context = context("corrupt")
        context.directory.deleteRecursively()
        try {
            Fixture(context).use { fixture ->
                fixture.seed("original")
                val journal = File(context.filesDir, "backup/restore-journal.json")
                journal.parentFile!!.mkdirs(); journal.writeText("broken")
                try { fixture.service().recoverIfNeeded(); fail("Must fail closed") }
                catch (_: BackupException) { }
                assertEquals("broken", journal.readText())
                assertEquals("original", fixture.db.ownerDao().findAll().single().id)
            }
        } finally {
            context.directory.deleteRecursively()
            DatasetGate.recoveryFinished()
            ApplicationReadiness.ready()
        }
    }

    /** Run separately with -e crashAt CHECKPOINT. This deliberately kills instrumentation. */
    @Test fun processDeathPrepare() = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("crashAt") ?: run {
            org.junit.Assume.assumeTrue("Dedicated process-kill probe requires crashAt argument", false)
            return@runBlocking
        }
        require(phase in checkpoints)
        val context = context("process-death")
        context.directory.deleteRecursively()
        Fixture(context).use { fixture ->
            val service = fixture.service()
            fixture.prepare(service)
            File(context.directory, "expected").writeText(
                if (phase in listOf("DATABASE_COMMITTED", "DB_COMMITTED")) "new" else "original")
            service.transitionHook = { if (it == phase) android.os.Process.killProcess(android.os.Process.myPid()) }
            service.applyConfirmed()
            fail("Process kill checkpoint not reached")
        }
    }

    /** Run after processDeathPrepare in a fresh instrumentation process. */
    @Test fun processDeathRecover() = runBlocking<Unit> {
        val context = context("process-death")
        val expectation = File(context.directory, "expected")
        org.junit.Assume.assumeTrue("Run processDeathPrepare in a preceding process first", expectation.isFile)
        val expected = expectation.readText()
        Fixture(context).use { fixture ->
            fixture.service().recoverIfNeeded()
            fixture.service().recoverIfNeeded()
            fixture.assertDataset(expected)
            assertFalse(fixture.storage.hasJournalFile())
        }
        context.directory.deleteRecursively()
    }

    /**
     * CD-01：已撤销批次的无目标历史必须能写入真实 staging 库（外键开启），
     * 恢复后的库必须通过 `PRAGMA foreign_key_check`，并且再导出→再恢复仍然成立。
     *
     * 该用例与 `realBulkCompletionCanBeRestoredAndUndone` 互补：后者覆盖"新撤销流程保留
     * 真实打卡记录行"，本用例覆盖"历史文件里目标已经不存在的明细"。
     */
    @Test fun undoneHistoricalBatchWithoutTargetSurvivesStagingForeignKeys() = runBlocking<Unit> {
        val sourceContext = context("cd01-source")
        val targetContext = context("cd01-target")
        val thirdContext = context("cd01-third")
        listOf(sourceContext, targetContext, thirdContext).forEach { it.directory.deleteRecursively() }
        try {
            val archive = File(sourceContext.directory, "cd01.dgbk")
            Fixture(sourceContext).use { source ->
                source.seed("new"); source.richHistory()
                // 历史形态：批次已撤销，明细指向一条已经不存在的打卡记录。
                source.db.completionBatchDao().insertBatch(
                    CompletionBatchEntity("legacy-batch", "legacy-operation", BatchScope.HOSTING_ORDER,
                        "hosting", LocalDate.of(2026, 10, 1), source.clock.now(),
                        source.clock.now().plusSeconds(60)),
                )
                source.db.completionBatchDao().insertItems(listOf(
                    CompletionBatchItemEntity("legacy-item", "legacy-batch", BatchItemType.OCCURRENCE,
                        null, "hosting-task-old", null, null, null, null, null,
                        source.clock.now(), source.clock.now(), false, null, "legacy-batch"),
                ))
                val snapshot = source.builder.build(
                    source.storage.newWorkDirectory("cd01-export"), source.clock.now(), null,
                )
                snapshot.file.copyTo(archive, overwrite = true)
            }

            Fixture(targetContext).use { target ->
                val service = target.service()
                service.inspect(Uri.fromFile(archive))
                val preview = service.prepare(null)
                // 该历史文件本身就是"无目标"形态（occurrenceId 原本为 null），因此无需改写，
                // 也不会有任何归一说明。"已知但悬空的 id"由
                // undoneDanglingTargetIsRejectedBeforeStagingReplacesLiveData 覆盖。
                assertTrue(preview.normalizerNotes.isEmpty())
                service.applyConfirmed()
                val item = target.db.completionBatchDao().findItems("legacy-batch").single()
                assertNull(item.occurrenceId)
                assertEquals("hosting-task-old", item.taskDefinitionId)
                assertNotNull(target.db.completionBatchDao().findById("legacy-batch")!!.undoneAt)
                assertTrue(preview.derivedSessionsRebuilt >= 0)
                // 附件与其它审计引用保持完整。
                assertEquals("image", target.db.orderDao().findAllAttachments().single().id)
                assertForeignKeysClean(target)
                // 已撤销的历史批次：重复撤销是 no-op，不删除任何行。
                val before = target.db.occurrenceDao().findForOrder("hosting").size
                val undo = target.repository().undoBatch("legacy-batch")
                assertTrue(undo.alreadyUndone)
                assertEquals(before, target.db.occurrenceDao().findForOrder("hosting").size)
                assertForeignKeysClean(target)
                // 再导出（撤销之后）仍然可以再次恢复。
                val again = File(targetContext.directory, "cd01-again.dgbk")
                val exported = target.builder.build(
                    target.storage.newWorkDirectory("cd01-reexport"), target.clock.now(), null,
                )
                exported.file.copyTo(again, overwrite = true)
                Fixture(thirdContext).use { third ->
                    val thirdService = third.service()
                    thirdService.inspect(Uri.fromFile(again))
                    thirdService.prepare(null)
                    thirdService.applyConfirmed()
                    val restored = third.db.completionBatchDao().findItems("legacy-batch").single()
                    assertNull(restored.occurrenceId)
                    assertEquals("hosting-task-old", restored.taskDefinitionId)
                    assertForeignKeysClean(third)
                }
            }
        } finally {
            listOf(sourceContext, targetContext, thirdContext).forEach { it.directory.deleteRecursively() }
        }
    }

    /**
     * CD-01 的拒绝边界（真实 SQLite + 真实 staging 路径）。
     *
     * 已撤销批次的明细带**已知但已不存在**的目标 id：这一类历史没有无损的持久化表示
     * （本端该列是 RESTRICT 外键），因此 `prepare` 必须在写入任何候选库 / staging 状态
     * 之前拒绝，live 数据集与归档字节都不变。
     */
    @Test fun undoneDanglingTargetIsRejectedBeforeStagingReplacesLiveData() = runBlocking<Unit> {
        val sourceContext = context("cd01-dangling-source")
        val targetContext = context("cd01-dangling-target")
        listOf(sourceContext, targetContext).forEach { it.directory.deleteRecursively() }
        try {
            val archive = File(sourceContext.directory, "cd01-dangling.dgbk")
            Fixture(sourceContext).use { source ->
                source.seed("original")
                val now = source.clock.now()
                val day = LocalDate.of(2026, 10, 1)
                source.db.gameAccountDao().insert(GameAccountEntity("account", "original", "游戏", "官服",
                    "uid", "login", LoginMethod.PASSWORD, null, now, now, null))
                source.db.orderDao().insert(OrderEntity("dangling-order", "account", OrderType.HOSTING,
                    "历史托管", null, PaymentMode.INSTALLMENTS, null, null, "Asia/Shanghai", 240, 1, 240,
                    now, now, null))
                source.db.orderDao().insertPeriod(HostingPeriodEntity("dangling-period", "dangling-order",
                    0, day, day, 1000, "Asia/Shanghai", 240, 1, 240, now, null))
                source.db.taskDao().insert(TaskDefinitionEntity("dangling-task", "dangling-order",
                    "dangling-period", "任务", ProgressType.CHECKBOX, BigDecimal.ZERO, BigDecimal.ONE,
                    BigDecimal.ZERO, null, RecurrenceType.DAILY, null, null, 0, null, null, now, now))
                // 批次已撤销；明细先指向真实行，再在导出的数据集上改写成已消失的 id
                // （真实 SQLite 的 RESTRICT 外键不允许这种行存在）。
                source.db.completionBatchDao().insertBatch(CompletionBatchEntity("dangling-batch",
                    "dangling-operation", BatchScope.HOSTING_ORDER, "dangling-order", day, now,
                    now.plusSeconds(60)))
                source.db.occurrenceDao().insertIgnore(TaskOccurrenceEntity("dangling-occurrence",
                    "dangling-task", "D:$day", day, day, day, now, now, false, null, null, now))
                source.db.completionBatchDao().insertItems(listOf(CompletionBatchItemEntity(
                    "dangling-item", "dangling-batch", BatchItemType.OCCURRENCE, "dangling-occurrence",
                    "dangling-task", null, null, null, null, null, now, now, false, null,
                    "dangling-batch")))

                val patched = withOccurrenceId(
                    source.access.dump(), "dangling-item", "deleted-occurrence-original-123")
                val dataBytes = BackupDataCodec.encode(patched).toByteArray(Charsets.UTF_8)
                val manifest = BackupManifest(
                    formatVersion = BackupFormat.FORMAT_VERSION,
                    schemaVersion = RECORD_DATABASE_VERSION,
                    exportedAt = now.toEpochMilli(),
                    generator = BackupFormat.GENERATOR,
                    dataSha256 = Sha256.of(dataBytes),
                    tableCounts = patched.tables.associate { it.table.name to it.rows.size },
                    attachments = emptyList(),
                    attachmentTotalBytes = 0,
                )
                FileOutputStream(archive).use { output ->
                    BackupArchiveWriter.write(output,
                        BackupManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8),
                        dataBytes, emptyList(), now.toEpochMilli())
                }
            }
            val archiveSha = Sha256.of(archive)

            Fixture(targetContext).use { target ->
                target.seed("original")
                val service = target.service()
                service.inspect(Uri.fromFile(archive))
                try {
                    service.prepare(null)
                    fail("已知悬空目标必须在激活前被拒绝")
                } catch (error: BackupException) {
                    val message = error.message.orEmpty()
                    assertTrue(message, message.contains("UNDONE_BATCH_TARGET_DANGLING"))
                    assertTrue(message, message.contains("deleted-occurrence-original-123"))
                }
                // live 数据集不变、没有会话/staging 状态被激活、归档字节未被就地修改。
                assertEquals("original", target.db.ownerDao().findAll().single().id)
                assertEquals(0, target.db.completionBatchDao().findItems("dangling-batch").size)
                assertFalse(service.hasPendingSession)
                assertFalse(target.storage.hasJournalFile())
                assertTrue(
                    "不应残留候选/staging 库",
                    File(targetContext.directory, "databases").listFiles()
                        .orEmpty().none { it.name.startsWith("restore-staging") },
                )
                assertForeignKeysClean(target)
                assertEquals(archiveSha, Sha256.of(archive))
            }
        } finally {
            listOf(sourceContext, targetContext).forEach { it.directory.deleteRecursively() }
        }
    }

    /** 把某行的 `occurrenceId` 改写成目标 id（只用于构造"已知但悬空"的历史输入）。 */
    private fun withOccurrenceId(
        dataSet: BackupDataSet,
        itemId: String,
        occurrenceId: String,
    ): BackupDataSet {
        val spec = checkNotNull(BackupTableCatalog.table("completion_batch_items"))
        val idIndex = spec.columnNames.indexOf("id")
        val occurrenceIndex = spec.columnNames.indexOf("occurrenceId")
        return BackupDataSet(
            dataSet.tables.map tableLoop@ { table ->
                if (table.table.name != "completion_batch_items") return@tableLoop table
                table.copy(rows = table.rows.map rowLoop@ { row ->
                    val id = (row[idIndex] as? JsonPrimitive)?.content
                    if (id != itemId) return@rowLoop row
                    row.toMutableList().also { it[occurrenceIndex] = JsonPrimitive(occurrenceId) }
                })
            },
        )
    }

    /** 真实外键与完整性检查：外键开启、`foreign_key_check` 与 `integrity_check` 均无行。 */
    private fun assertForeignKeysClean(fixture: Fixture) {
        val raw = fixture.db.openHelper.readableDatabase
        raw.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        raw.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
        raw.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }
    }
}
