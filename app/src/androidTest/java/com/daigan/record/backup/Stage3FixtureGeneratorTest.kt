package com.daigan.record.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.BuildConfig
import com.daigan.record.data.local.RECORD_DATABASE_VERSION
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.data.model.TemplateCategoryDraft
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.data.model.TemplateTaskDraft
import com.daigan.record.data.model.provenance
import com.daigan.record.data.repository.AttachmentSync
import com.daigan.record.data.repository.CorrectTotalCommand
import com.daigan.record.data.repository.CountSessionService
import com.daigan.record.data.repository.CumulativeProgressRepository
import com.daigan.record.data.repository.DefaultAccountCategoryRepository
import com.daigan.record.data.repository.DefaultCountSessionService
import com.daigan.record.data.repository.SubmitTotalCommand
import com.daigan.record.data.template.TemplateFileCodec
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.shanghaiRules
import com.daigan.record.testing.writeTestImage
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R3-6: production fixtures built through **actual repository commands** and exported
 * through the production [BackupExportService].
 *
 * Exact selector:
 * `com.daigan.record.backup.Stage3FixtureGeneratorTest#generateStage3Fixtures`
 *
 * Output (app-specific external storage, pulled with adb):
 * `Android/data/com.daigan.record/files/interop-fixtures/`
 *
 * This proves a production export exists for Android; it does **not** prove
 * cross-platform exchange (Stage 5 owns that acceptance).
 */
@RunWith(AndroidJUnit4::class)
class Stage3FixtureGeneratorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment
    private lateinit var progress: CumulativeProgressRepository
    private lateinit var sessions: CountSessionService
    private lateinit var dumpAccess: BackupDatabaseAccess

    private val day1 = LocalDate.parse("2026-10-17")
    private val day2 = LocalDate.parse("2026-10-18")

    /** Synthetic test-only password handled as test data, never a user secret. */
    private val syntheticPassword = "stage3-fixture-password".toCharArray()

    @Before
    fun setUp() {
        env = OrderTestEnvironment(context)
        sessions = DefaultCountSessionService(
            db = env.database,
            orderDao = env.database.orderDao(),
            taskDao = env.database.taskDao(),
            occurrenceDao = env.database.occurrenceDao(),
            eventDao = env.database.taskProgressEventDao(),
            sessionDao = env.database.hostingSessionDao(),
            accountDao = env.database.gameAccountDao(),
            ownerDao = env.database.ownerDao(),
            timeProvider = env.clock,
        )
        progress = CumulativeProgressRepository(
            db = env.database,
            orderDao = env.database.orderDao(),
            taskDao = env.database.taskDao(),
            occurrenceDao = env.database.occurrenceDao(),
            eventDao = env.database.taskProgressEventDao(),
            hostingSessionDao = env.database.hostingSessionDao(),
            accountDao = env.database.gameAccountDao(),
            ownerDao = env.database.ownerDao(),
            timeProvider = env.clock,
            countSessions = sessions,
        )
        dumpAccess = BackupDatabaseAccess(context, env.database, env.clock)
    }

    @After
    fun tearDown() {
        env.database.close()
    }

    @Test
    fun generateStage3Fixtures() = runBlocking {
        val outputDir = File(
            checkNotNull(context.getExternalFilesDir(null)) { "需要外部文件目录" },
            "interop-fixtures",
        ).apply { mkdirs() }
        outputDir.listFiles()?.forEach { it.delete() }

        val categories = DefaultAccountCategoryRepository(
            database = env.database,
            categoryDao = env.database.accountCategoryDao(),
            accountDao = env.database.gameAccountDao(),
            timeProvider = env.clock,
        )
        val mainCategory = categories.createCategory("主号")
        categories.createCategory("小号")
        val owner = env.ownerRepository.createOwner("老板甲")
        val account = env.accountRepository.createAccount(
            owner.id,
            AccountDraft(
                gameName = "游戏X",
                server = "官服",
                uid = "10001",
                loginAccount = "synthetic-10001",
                loginMethod = LoginMethod.VERIFICATION_CODE,
                categoryId = mainCategory.id,
            ),
        )

        // ---- Template format 4: title, category defaults, numeric mode, active window.
        val templateId = env.templateRepository.saveTemplate(
            TemplateDraft(
                gameName = "游戏X",
                templateVersion = "2026.10",
                defaultOrderTitle = "每日托管",
                hostingMode = HostingMode.COUNT_BASED,
                hostingModeExplicit = true,
                targetSessionsText = "2",
                categories = listOf(
                    TemplateCategoryDraft(
                        name = "主号",
                        hostingMode = HostingMode.COUNT_BASED,
                        targetSessionsText = "2",
                        tasks = listOf(
                            TemplateTaskDraft(title = "签到", progressType = ProgressType.CHECKBOX),
                            TemplateTaskDraft(
                                title = "抽数",
                                progressType = ProgressType.NUMERIC,
                                numericMode = NumericMode.CUMULATIVE,
                                startValue = "0",
                                targetValue = "100",
                                unit = "抽",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val template = checkNotNull(env.templateRepository.findTemplate(templateId))

        // ---- Count order: two periods, several rounds, mixed tasks, correction, receipts.
        val countOrderId = env.orderRepository.createHostingOrder(
            HostingOrderDraft(
                ownerId = owner.id,
                accountId = account.id,
                title = "打卡托管",
                startDate = day1,
                endDate = day1,
                priceYuan = "100.00",
                paymentMode = PaymentMode.INSTALLMENTS,
                refresh = shanghaiRules,
                tasks = listOf(
                    TaskDraft(title = "签到", recurrence = RecurrenceType.DAILY),
                    TaskDraft(
                        title = "抽数",
                        progressType = ProgressType.NUMERIC,
                        numericMode = NumericMode.CUMULATIVE,
                        startValue = "0",
                        targetValue = "100",
                        unit = "抽",
                        recurrence = RecurrenceType.DAILY,
                        colorArgb = 0xFF3366CC.toInt(),
                    ),
                ),
                hostingMode = HostingMode.COUNT_BASED,
                targetSessions = 2,
                sourceTemplate = template.provenance(),
                creationOperationId = UUID.randomUUID().toString(),
            ).copy(
                initialPayment = com.daigan.record.data.model.InitialPaymentDraft(
                    amountYuan = "50.00",
                    channel = PaymentChannel.WECHAT,
                    operationId = UUID.randomUUID().toString(),
                ),
            ),
        )
        env.orderRepository.setOrderColor(countOrderId, 0xFFCC3366.toInt())
        val countPeriod1 = env.database.orderDao().findPeriods(countOrderId).single()
        val countTasks = env.database.taskDao().findForPeriod(countPeriod1.id)
        val checkboxTask = countTasks.first { it.progressType == ProgressType.CHECKBOX }
        val cumulativeTask = countTasks.first { it.numericMode == NumericMode.CUMULATIVE }

        // Round 1 and round 2 on the same game day.
        val round1 = checkNotNull(env.database.hostingSessionDao().findByIndex(countOrderId, 1))
        env.orderRepository.setOccurrenceCompleted(checkboxTask.id, true, "N:1")
        progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                scope = ProgressScope.Session(round1.id),
                attributedGameDate = day1,
                total = java.math.BigDecimal("100"),
            ),
        )
        val round2 = progress.ensureCountSession(countOrderId).session
        env.orderRepository.setOccurrenceCompleted(checkboxTask.id, true, "N:2")
        progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                scope = ProgressScope.Session(round2.id),
                attributedGameDate = day1,
                total = java.math.BigDecimal("100"),
            ),
        )

        // Renewal period 2 (own target/price) and its round on the **same game day** (F1):
        // the renewed agreement starts serving the day it was created, even though the
        // storage anchor is pushed forward for the (orderId, startDate) uniqueness rule.
        val renewal = env.orderRepository.renewCountHosting(
            orderId = countOrderId,
            operationId = UUID.randomUUID().toString(),
            targetSessions = 1,
            priceCents = 5000,
        )
        val renewalTasks = env.database.taskDao().findForPeriod(renewal.periodId)
        val renewalCheckbox = renewalTasks.first { it.progressType == ProgressType.CHECKBOX }
        val renewalCumulative = renewalTasks.first { it.numericMode == NumericMode.CUMULATIVE }
        val round3 = progress.ensureCountSession(countOrderId).session
        env.orderRepository.setOccurrenceCompleted(renewalCheckbox.id, true, "N:3")
        val sameDayRenewed = progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = renewalCumulative.id,
                scope = ProgressScope.Session(round3.id),
                attributedGameDate = day1,
                total = java.math.BigDecimal("100"),
            ),
        )
        // The fixture itself asserts the two semantics it is meant to carry (F1/F3), so it
        // cannot silently drift into an "anchor as deadline" or "mirror of period 1" shape.
        assertEquals("same-day renewed work is attributed to the renewal day", day1, sameDayRenewed.event.gameDate)
        assertEquals(
            "orders.targetSessions mirrors the highest-periodIndex count period",
            1,
            env.database.orderDao().findById(countOrderId)?.targetSessions,
        )

        // The next game day: historical correction of round 1, below target then restored.
        env.clock.set(java.time.Instant.parse("2026-10-18T02:00:00Z"))
        val round1Event = progress.progressHistory(cumulativeTask.id)
            .first { it.scope == ProgressScope.Session(round1.id) && it.kind.name == "SUBMIT" }
        progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                targetEventId = round1Event.id,
                total = java.math.BigDecimal("80"),
            ),
        )
        progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulativeTask.id,
                scope = ProgressScope.Session(round1.id),
                attributedGameDate = day1,
                total = java.math.BigDecimal("100"),
            ),
        )
        env.receiptRepository.recordPayment(
            PaymentRequest(
                orderId = countOrderId,
                periodId = renewal.periodId,
                amountCents = 2500,
                receivedAt = env.clock.now(),
                channel = PaymentChannel.ALIPAY,
                note = "第二期部分收款",
                operationId = UUID.randomUUID().toString(),
            ),
        )

        // ---- Date-based hosting with monthly refresh windows and a completed occurrence.
        val datedOrderId = env.orderRepository.createHostingOrder(
            HostingOrderDraft(
                ownerId = owner.id,
                accountId = account.id,
                title = "月度刷新托管",
                startDate = day1,
                endDate = day1.plusDays(20),
                priceYuan = "88.00",
                paymentMode = PaymentMode.ONE_TIME,
                refresh = com.daigan.record.core.time.GameRefreshRules.fromMinutes(
                    zoneId = java.time.ZoneId.of("Asia/Shanghai"),
                    dailyRefreshMinutes = 240,
                    weeklyRefreshDayIso = 1,
                    weeklyRefreshMinutes = 300,
                    monthlyRefreshDays = setOf(1, 15),
                ),
                tasks = listOf(TaskDraft(title = "日常", recurrence = RecurrenceType.DAILY)),
                hostingMode = HostingMode.DATE_RANGE,
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
        val datedPeriod = env.database.orderDao().findPeriods(datedOrderId).single()
        val datedTask = env.database.taskDao().findForPeriod(datedPeriod.id).single()
        env.orderRepository.setHistoricalCompletion(
            orderId = datedOrderId,
            periodId = datedPeriod.id,
            taskId = datedTask.id,
            date = day1,
            completed = true,
        )

        // ---- Short order: cumulative goal, correction, batch completion history.
        val shortOrderId = env.orderRepository.createShortOrder(
            ShortOrderDraft(
                ownerId = owner.id,
                accountId = account.id,
                title = "短期累计",
                priceYuan = "30.00",
                tasks = listOf(
                    TaskDraft(title = "刷本", recurrence = RecurrenceType.ONE_TIME),
                    TaskDraft(
                        title = "累计击杀",
                        progressType = ProgressType.NUMERIC,
                        numericMode = NumericMode.CUMULATIVE,
                        startValue = "0",
                        targetValue = "50",
                        recurrence = RecurrenceType.ONE_TIME,
                    ),
                ),
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
        val shortTasks = env.database.taskDao().findForOrder(shortOrderId)
        val shortCheckbox = shortTasks.first { it.progressType == ProgressType.CHECKBOX }
        val shortCumulative = shortTasks.first { it.numericMode == NumericMode.CUMULATIVE }
        val shortSubmit = progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = shortCumulative.id,
                scope = ProgressScope.Task,
                attributedGameDate = day1,
                total = java.math.BigDecimal("30"),
            ),
        )
        progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = shortCumulative.id,
                targetEventId = shortSubmit.event.id,
                total = java.math.BigDecimal("40"),
            ),
        )
        val batch = env.orderRepository.completeShortOrder(shortOrderId, UUID.randomUUID().toString())
        env.orderRepository.undoBatch(batch.batchId)
        env.attachmentRepository.addAttachment(
            taskDefinitionId = shortCheckbox.id,
            occurrenceId = null,
            sourceUri = Uri.fromFile(writeTestImage(context, "stage3-fixture.jpg")),
        )

        // ---- Export through the production service: plain and encrypted.
        val summaryPlain = export(outputDir, "stage3-plain.dgbk", null)
        val summaryEncrypted = export(outputDir, "stage3-encrypted.dgbk", syntheticPassword)
        assertTrue(summaryPlain.archiveBytes > 0)
        assertTrue(summaryEncrypted.encrypted)
        // Read the plain fixture back through the production archive reader: this is an
        // Android-side round trip (not a cross-platform proof).
        val readBackDir = File(context.cacheDir, "stage3-fixture-readback")
            .apply { deleteRecursively(); mkdirs() }
        val extracted = BackupArchiveReader.read(
            File(outputDir, "stage3-plain.dgbk"),
            readBackDir,
            RECORD_DATABASE_VERSION,
        )
        assertTrue(extracted.manifest.schemaVersion > 0)
        assertTrue(extracted.dataBytes.isNotEmpty())
        assertTrue("attachment bytes are part of the archive", extracted.attachmentsDirectory.listFiles().orEmpty().isNotEmpty())
        val encryptedMagic = File(outputDir, "stage3-encrypted.dgbk").inputStream().use { input ->
            val bytes = ByteArray(8)
            var read = 0
            while (read < bytes.size) {
                val count = input.read(bytes, read, bytes.size - read)
                if (count <= 0) break
                read += count
            }
            String(bytes, Charsets.US_ASCII)
        }
        assertTrue("encrypted envelope magic", encryptedMagic == "DAIGANBK")

        // ---- Template format 4 file.
        val templateFile = File(outputDir, "stage3-template-v4.json")
        templateFile.writeBytes(TemplateFileCodec.encode(template))

        // ---- Expected normalized business snapshot (same dump the exporter used).
        val dataSet = dumpAccess.dump()
        val normalized = File(outputDir, "stage3-expected-snapshot.json")
        normalized.writeText(BackupDataCodec.encode(dataSet), Charsets.UTF_8)

        // The exported derived state must already equal the restore reconstruction, including
        // the latest-period target mirror (F3) and the retirement-aware due sets (F2). A
        // reader that trusts derived columns therefore cannot disagree with this file.
        val rebuilt = DerivedStateReconstructor.reconstruct(dataSet)
        assertTrue(
            "exported derived state must equal the reconstruction: ${rebuilt.report}",
            BackupDataCodec.encode(dataSet) == BackupDataCodec.encode(rebuilt.dataSet),
        )

        // ---- Provenance + manifest with per-file SHA-256.
        val attachmentFiles = File(context.filesDir, "attachments").listFiles().orEmpty()
        val provenance = buildJsonObject {
            put("generatedBy", "com.daigan.record.backup.Stage3FixtureGeneratorTest#generateStage3Fixtures")
            put("appVersionName", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            put("apiLevel", android.os.Build.VERSION.SDK_INT)
            put("exportedAt", env.clock.now().toString())
            put("portableSchema", 15)
            put("templateFormat", 4)
            put("syntheticDataOnly", true)
            put("encryptedFixturePassword", "stage3-fixture-password")
            put("provenanceNote", "Built from disposable synthetic data through repository commands; not real user data. Cross-platform exchange is not proven here.")
            putJsonArray("attachments") {
                attachmentFiles.sortedBy { it.name }.forEach { file ->
                    add(JsonObject(mapOf(
                        "file" to JsonPrimitive(file.name),
                        "sizeBytes" to JsonPrimitive(file.length()),
                        "sha256" to JsonPrimitive(sha256(file)),
                    )))
                }
            }
            putJsonArray("files") {
                outputDir.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                    add(JsonObject(mapOf(
                        "file" to JsonPrimitive(file.name),
                        "sizeBytes" to JsonPrimitive(file.length()),
                        "sha256" to JsonPrimitive(sha256(file)),
                    )))
                }
            }
            putJsonObject("rowCounts") {
                dataSet.tables.forEach { table ->
                    put(table.table.name, table.rows.size)
                }
            }
        }
        File(outputDir, "stage3-provenance.json").writeText(
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), provenance),
            Charsets.UTF_8,
        )
    }

    private suspend fun export(
        outputDir: File,
        fileName: String,
        password: CharArray?,
    ): BackupExportSummary {
        val storage = BackupStorage(context, env.clock)
        val service = BackupExportService(
            storage = storage,
            snapshotBuilder = BackupSnapshotBuilder(storage, dumpAccess),
            attachmentSync = AttachmentSync(),
            documentStore = FileDocumentStore(File(outputDir, fileName)),
            timeProvider = env.clock,
        )
        return service.export(Uri.EMPTY, password?.copyOf())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Test-only document store: the production service writes the archive to a file. */
    private class FileDocumentStore(private val target: File) : BackupDocumentStore {
        override suspend fun copyDocumentToFile(uri: Uri, target: File, maxBytes: Long): Long =
            error("fixtures are write-only")

        override suspend fun writeFileToDocument(uri: Uri, source: File) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }

        override fun displayName(uri: Uri): String = target.name

        override fun hasRoomFor(bytes: Long): Boolean = true
    }
}
