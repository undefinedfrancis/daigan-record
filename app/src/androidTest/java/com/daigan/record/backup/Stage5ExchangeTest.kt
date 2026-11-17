package com.daigan.record.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daigan.record.BuildConfig
import com.daigan.record.core.time.ReportingTimeZoneProvider
import com.daigan.record.data.local.RECORD_DATABASE_VERSION
import com.daigan.record.data.local.entity.TaskDefinitionEntity
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.CountHosting
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.InitialPaymentDraft
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.PaymentRequest
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.ReceiptCorrectionKind
import com.daigan.record.data.model.ReceiptCorrectionRequest
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.data.model.TemplateCategoryDraft
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.data.model.TemplateTaskDraft
import com.daigan.record.data.model.provenance
import com.daigan.record.data.repository.CorrectTotalCommand
import com.daigan.record.data.repository.CountSessionService
import com.daigan.record.data.repository.CumulativeProgressRepository
import com.daigan.record.data.repository.DatasetGate
import com.daigan.record.data.repository.DefaultAccountCategoryRepository
import com.daigan.record.data.repository.DefaultCountSessionService
import com.daigan.record.data.repository.ProgressCommandResult
import com.daigan.record.data.repository.SubmitTotalCommand
import com.daigan.record.data.template.TemplateFileCodec
import com.daigan.record.feature.orders.OrderDetailViewModel
import com.daigan.record.notifications.DefaultReminderSettingsRepository
import com.daigan.record.notifications.ReminderCategory
import com.daigan.record.notifications.ReminderCoordinator
import com.daigan.record.notifications.ReminderMessage
import com.daigan.record.notifications.ReminderNotifier
import com.daigan.record.notifications.ReminderRepository
import com.daigan.record.notifications.ReminderScheduler
import com.daigan.record.notifications.ReminderSettings
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.testing.hostingSnapshot
import com.daigan.record.testing.shanghaiRules
import com.daigan.record.testing.writeTestJpeg
import com.daigan.record.ui.navigation.Routes
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 5 — real cross-platform exchange on Android.
 *
 * Everything here uses the **production** restore/export services against a real Room
 * database and a real `.dgbk` archive (real ZIP + AES-GCM), inside a private context so
 * the app's own files/databases are never touched. Files cross the platform boundary
 * through the app-specific external directory
 * (`Android/data/com.daigan.record/files/interop-stage5/{in,out}`), which the host fills
 * and pulls with adb.
 *
 * Groups:
 * - `a*` sender / legacy-fixture authoring.
 * - `b*` receiver: restore the browser's production exports, verify the persisted rows
 *   through production queries and the real `OrderDetailViewModel`, continue working,
 *   re-export.
 * - `c*` adverse: rejection boundaries that must leave the active dataset intact.
 */
@RunWith(AndroidJUnit4::class)
class Stage5ExchangeTest {

    companion object {
        /** Synthetic test password with non-ASCII characters; never a user secret. */
        const val UNICODE_PASSWORD = "stage5-互通-Ω-口令"
        const val PLAIN_INPUT = "web-chain2-plain.dgbk"
        const val ENCRYPTED_INPUT = "web-chain2-encrypted.dgbk"
        const val CHAIN1_CONTINUED_INPUT = "web-chain1-continued.dgbk"
        const val CHAIN1_NOEDIT_INPUT = "web-chain1-noedit.dgbk"
        const val ANDROID_ENCRYPTED = "android-chain1-encrypted.dgbk"
        const val LEGACY14_INPUT = "legacy-android14.dgbk"

        /**
         * Columns that genuinely did not exist in portable schema 14 (they are a subset of
         * the product's own `legacyOptionalColumns`). Dropping exactly these produces a
         * structurally valid historical file; nothing newer is kept and nothing older lost.
         */
        val SCHEMA14_DROPPED_COLUMNS = mapOf(
            "game_accounts" to listOf("categoryId"),
            "game_templates" to listOf("serviceWeekdays", "defaultOrderTitle"),
            "template_categories" to listOf("hostingMode", "targetSessions", "serviceWeekdays"),
            "template_tasks" to listOf("activeFrom", "activeUntil", "numericMode"),
            "orders" to listOf("monthlyRefreshDays"),
            "hosting_periods" to listOf("monthlyRefreshDays", "serviceCount"),
            "hosting_sessions" to listOf("periodId"),
            "task_definitions" to listOf("activeUntil", "numericMode", "colorArgb"),
        )
        val SCHEMA14_ABSENT_TABLES = setOf("account_categories", "task_progress_events")
    }

    private val day1 = LocalDate.parse("2026-03-10")
    private val day2 = LocalDate.parse("2026-03-11")
    private val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Host exchange directory (real external storage of the app under test). */
    private val exchangeRoot: File = File(
        checkNotNull(targetContext.getExternalFilesDir(null)) { "需要外部文件目录" },
        "interop-stage5",
    )
    private val inDir = File(exchangeRoot, "in")
    private val outDir = File(exchangeRoot, "out")

    private lateinit var caseDir: File
    private lateinit var context: PrivateContext
    private lateinit var env: OrderTestEnvironment
    private lateinit var progress: CumulativeProgressRepository
    private lateinit var sessions: CountSessionService
    private lateinit var storage: BackupStorage
    private lateinit var access: BackupDatabaseAccess
    private lateinit var builder: BackupSnapshotBuilder
    private lateinit var categoryRepository: DefaultAccountCategoryRepository

    /** Filesystem isolation: the production services see only this directory tree. */
    private class PrivateContext(base: Context, val directory: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(directory, "databases/$name").apply {
            parentFile!!.mkdirs()
        }
        override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()
    }

    @Before
    fun setUp() {
        exchangeRoot.mkdirs()
        inDir.mkdirs()
        outDir.mkdirs()
        caseDir = File(targetContext.cacheDir, "stage5-${System.nanoTime()}")
        caseDir.deleteRecursively()
        context = PrivateContext(targetContext, caseDir)
        // 2026-03-10 10:00 in Shanghai: the game date every fixture is attributed to.
        env = OrderTestEnvironment(
            context,
            initialInstant = Instant.parse("2026-03-10T02:00:00Z"),
            databaseName = "stage5-live.db",
            inMemory = false,
        )
        storage = BackupStorage(context, env.clock)
        access = BackupDatabaseAccess(context, env.database, env.clock)
        builder = BackupSnapshotBuilder(storage, access)
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
        categoryRepository = DefaultAccountCategoryRepository(
            database = env.database,
            categoryDao = env.database.accountCategoryDao(),
            accountDao = env.database.gameAccountDao(),
            timeProvider = env.clock,
        )
    }

    @After
    fun tearDown() {
        env.database.close()
        caseDir.deleteRecursively()
        DatasetGate.recoveryFinished()
    }

    // ------------------------------------------------------------------ sender

    /**
     * Chain-1 sender: an Android dataset exported through the production service.
     *
     * Covers UF-036 (template default title), UF-037 (two accounts with identical visible
     * names under different owners + categories), UF-038 (cumulative absolute totals, two
     * rounds in one day, a same-day renewal period, a historical correction, a retired
     * task) and a genuine JPEG attached through the production attachment command.
     */
    @Test
    fun a1ExportChain1SenderFixtures() = runBlocking<Unit> {
        val fixture = buildSenderDataset()

        val plainFile = export("android-chain1-plain.dgbk", null)
        val encryptedFile = export(ANDROID_ENCRYPTED, UNICODE_PASSWORD.toCharArray())
        File(outDir, "android-chain1-template-v4.json").writeBytes(
            TemplateFileCodec.encode(fixture.template),
        )

        assertTrue(plainFile.length() > 0)
        assertTrue("encrypted envelope", BackupCrypto.isEncrypted(encryptedFile))
        val decrypted = File(caseDir, "decrypted-check.zip")
        FileOutputStream(decrypted).use { output ->
            BackupCrypto.decryptTo(encryptedFile, output, UNICODE_PASSWORD.toCharArray())
        }
        assertTrue("Unicode password opens the archive", BackupCrypto.isZip(decrypted))

        writeCanonical(plainFile, "android-chain1-canonical.json")
        writeProvenance(
            file = "android-chain1-provenance.json",
            generator = "a1ExportChain1SenderFixtures",
            description = "Android production export of a synthetic dataset built through repository commands",
            archives = listOf(
                Triple("android-chain1-plain.dgbk", plainFile, null),
                Triple(ANDROID_ENCRYPTED, encryptedFile, UNICODE_PASSWORD),
            ),
            extras = listOf(
                Pair("android-chain1-template-v4.json", File(outDir, "android-chain1-template-v4.json")),
                Pair("android-chain1-canonical.json", File(outDir, "android-chain1-canonical.json")),
            ),
            extraJson = buildJsonObject {
                put("attachmentSha256", fixture.attachmentSha256)
                put("attachmentBytes", fixture.attachmentBytes)
                put("attachmentRelativePath", fixture.attachmentRelativePath)
                put("attachmentOwnsTask", fixture.attachmentTaskId)
                put("countOrderId", fixture.countOrderId)
                put("cumulativeTaskId", fixture.cumulativeTaskId)
                put("firstRoundId", fixture.round1Id)
                put("retiredTaskId", fixture.retiredTaskId)
                put("scenarioIds", "S-27,S-30,S-33,S-36,S-38,S-42,S-50,S-57,S-58")
            },
        )
    }

    /**
     * Legacy fixture authoring: a **structural** schema-14 archive (all vnext tables and
     * columns removed, count periods without `serviceCount`, sessions without `periodId`)
     * built with the production data encoder so the old shape is genuinely valid.
     */
    @Test
    fun a2GenerateSyntheticSchema14Fixture() = runBlocking<Unit> {
        val owner = env.ownerRepository.createOwner("旧版老板")
        val account = env.accountRepository.createAccount(
            owner.id,
            AccountDraft(
                gameName = "旧版游戏",
                server = "官服",
                uid = "legacy-uid-1",
                loginAccount = "legacy-login",
                loginMethod = LoginMethod.VERIFICATION_CODE,
            ),
        )
        // A dated order ...
        val datedOrder = env.orderRepository.createHostingOrder(
            HostingOrderDraft(
                ownerId = owner.id,
                accountId = account.id,
                title = "旧版日期托管",
                startDate = day1,
                endDate = day1.plusDays(5),
                priceYuan = "60.00",
                paymentMode = PaymentMode.ONE_TIME,
                refresh = shanghaiRules,
                tasks = listOf(TaskDraft(title = "日常", recurrence = RecurrenceType.DAILY)),
                hostingMode = HostingMode.DATE_RANGE,
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
        // ... and a schema-13-era count order (target on the order, one container period).
        val countOrder = env.orderRepository.createHostingOrder(
            HostingOrderDraft(
                ownerId = owner.id,
                accountId = account.id,
                title = "旧版打卡托管",
                startDate = day1,
                endDate = day1,
                priceYuan = "120.00",
                paymentMode = PaymentMode.INSTALLMENTS,
                refresh = shanghaiRules,
                tasks = listOf(TaskDraft(title = "签到", recurrence = RecurrenceType.DAILY)),
                hostingMode = HostingMode.COUNT_BASED,
                targetSessions = 3,
                creationOperationId = UUID.randomUUID().toString(),
            ),
        )
        env.orderRepository.setOccurrenceCompleted(
            env.database.taskDao().findForPeriod(
                env.database.orderDao().findPeriods(countOrder).single().id,
            ).single().id,
            true,
            "N:1",
        )
        val round2 = progress.ensureCountSession(countOrder).session
        assertTrue(round2.sessionIndex > 1)
        env.receiptRepository.recordPayment(
            PaymentRequest(
                orderId = countOrder,
                periodId = env.database.orderDao().findPeriods(countOrder).single().id,
                amountCents = 2000,
                receivedAt = env.clock.now(),
                channel = PaymentChannel.WECHAT,
                note = "旧版定金",
                operationId = UUID.randomUUID().toString(),
            ),
        )

        val current = access.dump()
        // Preconditions: this dataset uses no schema-15-only value, so dropping the new
        // structure cannot lose business meaning.
        val taskSpec = checkNotNull(BackupTableCatalog.table("task_definitions"))
        current.rows("task_definitions").forEach { row ->
            assertNull(
                "no cumulative task in the legacy fixture",
                (row.getOrNull(taskSpec.columnNames.indexOf("numericMode")) as? JsonPrimitive)?.content,
            )
            assertNull((row.getOrNull(taskSpec.columnNames.indexOf("colorArgb")) as? JsonPrimitive)?.content)
        }
        assertTrue(current.rows("account_categories").isEmpty())
        assertTrue(current.rows("task_progress_events").isEmpty())
        val orderSpec = checkNotNull(BackupTableCatalog.table("orders"))
        assertTrue(current.rows("orders").all { row ->
            (row.getOrNull(orderSpec.columnNames.indexOf("monthlyRefreshDays")) as? JsonPrimitive)?.content == null
        })
        assertEquals(
            "the schema-14 count target lives on the order",
            3,
            current.rows("orders")
                .first { (it.getOrNull(orderSpec.columnNames.indexOf("id")) as? JsonPrimitive)?.content == countOrder }
                .let { (it.getOrNull(orderSpec.columnNames.indexOf("targetSessions")) as? JsonPrimitive)?.content?.toInt() },
        )
        // The live dataset does carry the deterministic schema-15 columns; the file below
        // is what removes them, and b6 proves the normalizer can rebuild them.
        // Structural downgrade: drop the schema-15 tables and columns.
        val downgraded = BackupDataSet(
            current.tables
                .filter { it.table.name !in SCHEMA14_ABSENT_TABLES }
                .map { tableRows ->
                    val drops = SCHEMA14_DROPPED_COLUMNS[tableRows.table.name].orEmpty()
                    if (drops.isEmpty()) {
                        tableRows
                    } else {
                        val keep = tableRows.table.columns.filterNot { it.name in drops }
                        BackupDataSet.TableRows(
                            table = tableRows.table.copy(columns = keep),
                            rows = tableRows.rows.map { row ->
                                row.filterIndexed { index, _ ->
                                    tableRows.table.columns[index].name !in drops
                                }
                            },
                        )
                    }
                },
        )
        val encoded = BackupDataCodec.encode(downgraded)
        // The encoder always stamps the current schema in the payload; a historical file
        // declares its own. Exactly one occurrence is rewritten and the value is asserted.
        val declared = "\"schemaVersion\":$RECORD_DATABASE_VERSION"
        assertEquals(
            "the encoder declares the portable schema exactly once",
            1,
            Regex(Regex.escape(declared)).findAll(encoded).count(),
        )
        val legacyData = encoded.replace(declared, "\"schemaVersion\":14")
        val legacyBytes = legacyData.toByteArray(Charsets.UTF_8)
        // Round-trip through the production decoder with schemaVersion 14: the file must be
        // self-consistent before it is ever used as legacy input.
        val redecoded = BackupDataCodec.decode(legacyData, schemaVersion = 14)
        assertEquals(2, redecoded.rows("orders").size)
        assertEquals(2, redecoded.rows("hosting_sessions").size)

        val legacyManifest = BackupManifest(
            formatVersion = BackupFormat.FORMAT_VERSION,
            schemaVersion = 14,
            exportedAt = env.clock.now().toEpochMilli(),
            generator = "Stage5ExchangeTest#a2GenerateSyntheticSchema14Fixture",
            dataSha256 = Sha256.of(legacyBytes),
            tableCounts = downgraded.tables.associate { it.table.name to it.rows.size },
            attachments = emptyList(),
            attachmentTotalBytes = 0,
        )
        val target = File(outDir, LEGACY14_INPUT)
        FileOutputStream(target).use { output ->
            BackupArchiveWriter.write(
                output,
                BackupManifestCodec.encode(legacyManifest).toByteArray(Charsets.UTF_8),
                legacyBytes,
                emptyList(),
                env.clock.now().toEpochMilli(),
            )
        }
        // The production reader accepts it (migrations do the semantic backfill later).
        val staging = File(caseDir, "legacy-readback").apply { mkdirs() }
        val readBack = BackupArchiveReader.read(target, staging, RECORD_DATABASE_VERSION)
        assertEquals(14, readBack.manifest.schemaVersion)

        writeProvenance(
            "legacy-android14-provenance.json",
            "a2GenerateSyntheticSchema14Fixture",
            "Synthetic structural schema-14 archive (vnext tables/columns removed, order-level count target)",
            listOf(Triple(LEGACY14_INPUT, target, null)),
            emptyList(),
            buildJsonObject {
                put("fixtureKind", "synthetic-historical-schema14")
                put("notGenuineHistoricalUserFile", true)
                put("droppedTables", JsonArray(SCHEMA14_ABSENT_TABLES.map { JsonPrimitive(it) }))
                put("droppedColumns", buildJsonObject {
                    SCHEMA14_DROPPED_COLUMNS.forEach { (table, columns) ->
                        put(table, JsonPrimitive(columns.joinToString(",")))
                    }
                })
                put("datedOrderId", datedOrder)
                put("countOrderId", countOrder)
            },
        )
    }

    private data class SenderFixture(
        val template: com.daigan.record.data.model.GameTemplate,
        val attachmentSha256: String,
        val attachmentBytes: Int,
        val attachmentRelativePath: String,
        val attachmentTaskId: String,
        val countOrderId: String,
        val cumulativeTaskId: String,
        val round1Id: String,
        val retiredTaskId: String,
    )

    private suspend fun buildSenderDataset(): SenderFixture {
        // UF-037: two categories, two owners, and an account whose visible fields are
        // identical to another owner's account (only the stable ids differ).
        val mainCategory = categoryRepository.createCategory("主号")
        categoryRepository.createCategory("小号")
        val ownerA = env.ownerRepository.createOwner("老板甲")
        val ownerB = env.ownerRepository.createOwner("老板乙")
        val accountA = env.accountRepository.createAccount(
            ownerA.id,
            AccountDraft(
                gameName = "游戏X",
                server = "官服",
                uid = "10001",
                loginAccount = "synthetic-10001",
                loginMethod = LoginMethod.VERIFICATION_CODE,
                categoryId = mainCategory.id,
            ),
        )
        val accountB = env.accountRepository.createAccount(
            ownerB.id,
            AccountDraft(
                gameName = "游戏X",
                server = "官服",
                uid = "10001",
                loginAccount = "synthetic-10001",
                loginMethod = LoginMethod.VERIFICATION_CODE,
                categoryId = mainCategory.id,
            ),
        )
        assertNotEquals(accountA.id, accountB.id)

        // UF-036: template with a default order title (format 4 file written by the caller).
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

        // UF-038: count hosting with two rounds on the creation day, then a same-day
        // renewal with its own target/price (service starts the day it is created).
        val countOrderId = env.orderRepository.createHostingOrder(
            HostingOrderDraft(
                ownerId = ownerA.id,
                accountId = accountA.id,
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
                    TaskDraft(title = "将被移除", recurrence = RecurrenceType.DAILY),
                ),
                hostingMode = HostingMode.COUNT_BASED,
                targetSessions = 2,
                sourceTemplate = template.provenance(),
                creationOperationId = UUID.randomUUID().toString(),
            ).copy(
                initialPayment = InitialPaymentDraft(
                    amountYuan = "50.00",
                    channel = PaymentChannel.WECHAT,
                    operationId = UUID.randomUUID().toString(),
                ),
            ),
        )
        env.orderRepository.setOrderColor(countOrderId, 0xFFCC3366.toInt())
        val period1 = env.database.orderDao().findPeriods(countOrderId).single()
        val tasks = env.database.taskDao().findForPeriod(period1.id)
        val signIn = tasks.first { it.progressType == ProgressType.CHECKBOX }
        val cumulative = tasks.first { it.numericMode == NumericMode.CUMULATIVE }
        val toRetire = tasks.first { it.title == "将被移除" }

        val round1 = checkNotNull(env.database.hostingSessionDao().findByIndex(countOrderId, 1))
        tasks.filter { it.progressType == ProgressType.CHECKBOX }.forEach {
            env.orderRepository.setOccurrenceCompleted(it.id, true, "N:1")
        }
        submit(cumulative.id, ProgressScope.Session(round1.id), day1, "100")
        val round2 = progress.ensureCountSession(countOrderId).session
        assertEquals("round 1 completed, so a second round is allocated", 2, round2.sessionIndex)
        tasks.filter { it.progressType == ProgressType.CHECKBOX }.forEach {
            env.orderRepository.setOccurrenceCompleted(it.id, true, "N:2")
        }
        submit(cumulative.id, ProgressScope.Session(round2.id), day1, "100")

        // Same-day renewal: its own period target 1, its own price, round 3.
        val renewal = env.orderRepository.renewCountHosting(
            orderId = countOrderId,
            operationId = UUID.randomUUID().toString(),
            targetSessions = 1,
            priceCents = 5000,
        )
        val renewalTasks = env.database.taskDao().findForPeriod(renewal.periodId)
        val renewalCumulative = renewalTasks.first { it.numericMode == NumericMode.CUMULATIVE }
        val round3 = progress.ensureCountSession(countOrderId).session
        assertEquals("the renewed agreement starts its own round", 3, round3.sessionIndex)
        renewalTasks.filter { it.progressType == ProgressType.CHECKBOX }.forEach {
            env.orderRepository.setOccurrenceCompleted(it.id, true, "N:3")
        }
        submit(renewalCumulative.id, ProgressScope.Session(round3.id), day1, "100")
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

        // Retire one task through the production command, then correct the first round:
        // the restored dataset must keep both the retirement and the correction chain.
        env.clock.set(Instant.parse("2026-03-11T02:00:00Z"))
        env.orderRepository.updateTaskDetails(
            countOrderId,
            listOf(signIn, cumulative).map { task: TaskDefinitionEntity ->
                TaskDraft(
                    id = task.id,
                    periodId = period1.id,
                    title = task.title,
                    progressType = task.progressType,
                    recurrence = task.recurrence,
                    numericMode = task.numericMode,
                )
            },
        )
        assertNotNull(
            "retirement is recorded",
            checkNotNull(env.database.taskDao().findById(toRetire.id)).retiredAt,
        )
        val round1Event = progress.progressHistory(cumulative.id)
            .first { it.scope == ProgressScope.Session(round1.id) && it.kind.name == "SUBMIT" }
        progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = cumulative.id,
                targetEventId = round1Event.id,
                total = BigDecimal("80"),
            ),
        )

        // Dated hosting with month refresh days and a completed historical date.
        env.clock.set(Instant.parse("2026-03-10T02:00:00Z"))
        val datedOrderId = env.orderRepository.createHostingOrder(
            HostingOrderDraft(
                ownerId = ownerB.id,
                accountId = accountB.id,
                title = "月度刷新托管",
                startDate = day1,
                endDate = day1.plusDays(20),
                priceYuan = "88.00",
                paymentMode = PaymentMode.ONE_TIME,
                refresh = com.daigan.record.core.time.GameRefreshRules.fromMinutes(
                    zoneId = ZoneId.of("Asia/Shanghai"),
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
        // A real bulk completion (then undone) so the archive carries an OCCURRENCE batch
        // item: that is the shape the CD-01 boundary is about.
        val bulk = env.orderRepository.completeHostingOrder(
            datedOrderId,
            UUID.randomUUID().toString(),
            env.hostingSnapshot(datedOrderId),
        )
        assertTrue("bulk completion wrote at least one item", bulk.completedCount > 0)
        env.orderRepository.undoBatch(bulk.batchId)
        env.orderRepository.setHistoricalCompletion(
            orderId = datedOrderId,
            periodId = datedPeriod.id,
            taskId = datedTask.id,
            date = day1,
            completed = true,
        )

        // Short order: cumulative goal + real JPEG through the production attachment path.
        val shortOrderId = env.orderRepository.createShortOrder(
            ShortOrderDraft(
                ownerId = ownerA.id,
                accountId = accountA.id,
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
                total = BigDecimal("30"),
            ),
        ).event
        progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = shortCumulative.id,
                targetEventId = shortSubmit.id,
                total = BigDecimal("40"),
            ),
        )
        val batch = env.orderRepository.completeShortOrder(shortOrderId, UUID.randomUUID().toString())
        env.orderRepository.undoBatch(batch.batchId)

        // A genuine decodable JPEG (Android's own encoder) added through the production
        // attachment command: the receiver checks the real bytes, never a marker.
        val jpeg = writeTestJpeg(context, "stage5-checkin.jpg")
        val jpegBytes = jpeg.readBytes()
        assertTrue("JPEG SOI", jpegBytes[0] == 0xFF.toByte() && jpegBytes[1] == 0xD8.toByte())
        val attachment = env.attachmentRepository.addAttachment(
            taskDefinitionId = shortCheckbox.id,
            occurrenceId = null,
            sourceUri = Uri.fromFile(jpeg),
        )

        // A receipt amendment so the audit chain is exchanged too.
        val receipt = env.receiptRepository.recordPayment(
            PaymentRequest(
                orderId = shortOrderId,
                periodId = null,
                amountCents = 3000,
                receivedAt = env.clock.now(),
                channel = PaymentChannel.CASH,
                note = "短期单一次结清",
                operationId = UUID.randomUUID().toString(),
            ),
        ).receipt
        env.receiptRepository.correctReceipt(
            ReceiptCorrectionRequest.Amend(
                operationId = UUID.randomUUID().toString(),
                receiptId = receipt.id,
                amountCents = 2500,
                receivedAt = env.clock.now(),
                channel = PaymentChannel.WECHAT,
                note = "改为部分收款并留欠款",
            ),
        )

        return SenderFixture(
            template = template,
            attachmentSha256 = sha256(jpegBytes),
            attachmentBytes = jpegBytes.size,
            attachmentRelativePath = attachment.relativePath,
            attachmentTaskId = shortCheckbox.id,
            countOrderId = countOrderId,
            cumulativeTaskId = cumulative.id,
            round1Id = round1.id,
            retiredTaskId = toRetire.id,
        )
    }

    // ---------------------------------------------------------------- receiver

    /** Chain 2 (plain): browser export → Android production restore → continue → export. */
    @Test
    fun b1RestoreWebPlainVerifyAndContinue() = runBlocking<Unit> {
        val source = inputFile(PLAIN_INPUT)
        val preview = restore(source, null)
        assertEquals("synthetic browser dataset", 3, preview.orders)

        verifyRestoredWebDataset()
        continueOnAndroid("android-chain2-continued.dgbk", "b1RestoreWebPlainVerifyAndContinue")
    }

    /** Same leg through an encrypted file whose password is non-ASCII (Unicode). */
    @Test
    fun b2RestoreWebEncryptedUnicodeAndContinue() = runBlocking<Unit> {
        val source = inputFile(ENCRYPTED_INPUT)
        val inspection = restoreService().inspect(Uri.fromFile(source))
        assertTrue("the browser file is encrypted", inspection.encrypted)
        val preview = restore(source, UNICODE_PASSWORD.toCharArray())
        assertEquals(3, preview.orders)
        verifyRestoredWebDataset()
        continueOnAndroid("android-chain2-encrypted-continued.dgbk", "b2RestoreWebEncryptedUnicodeAndContinue")
    }

    /**
     * Chain-1 return leg: restore the browser's *continued* export of the Android dataset,
     * check the browser's edits arrived, keep working, and export for the browser.
     */
    @Test
    fun b3RestoreChain1ContinuationAndExport() = runBlocking<Unit> {
        val source = inputFile(CHAIN1_CONTINUED_INPUT)
        val preview = restore(source, null)
        assertTrue(preview.orders >= 3)

        val data = access.dump()
        val categorySpec = checkNotNull(BackupTableCatalog.table("account_categories"))
        val categoryNames = data.rows("account_categories").mapNotNull { row ->
            (row.getOrNull(categorySpec.columnNames.indexOf("name")) as? JsonPrimitive)?.content
        }
        assertTrue(
            "browser-created category survives: $categoryNames",
            categoryNames.contains("网页续用分类"),
        )
        val taskSpec = checkNotNull(BackupTableCatalog.table("task_definitions"))
        val webTaskRows = data.rows("task_definitions").filter { row ->
            val title = (row.getOrNull(taskSpec.columnNames.indexOf("title")) as? JsonPrimitive)?.content
            val mode = (row.getOrNull(taskSpec.columnNames.indexOf("numericMode")) as? JsonPrimitive)?.content
            title == "网页累计任务" && mode == "CUMULATIVE"
        }
        assertTrue("browser cumulative task survives", webTaskRows.isNotEmpty())
        val webTaskId = (webTaskRows.first()
            .getOrNull(taskSpec.columnNames.indexOf("id")) as? JsonPrimitive)!!.content
        val webTaskOrderId = (webTaskRows.first()
            .getOrNull(taskSpec.columnNames.indexOf("orderId")) as? JsonPrimitive)!!.content

        // The browser's cumulative total arrived as an absolute value (10 -> 15 = 15).
        val webScope = scopeOf(webTaskId)
        val restoredStatus = checkNotNull(progress.cumulativeStatus(webTaskId, webScope))
        assertEquals(
            "the browser's absolute total survives the exchange",
            0,
            BigDecimal("15").compareTo(restoredStatus.total),
        )

        // Continue on Android; the browser restores this file next.
        env.clock.set(Instant.parse("2026-03-12T02:00:00Z"))
        val continued = submit(webTaskId, webScope, day2.plusDays(1), "25")
        assertEquals("absolute total replaced", 0, BigDecimal("25").compareTo(continued.snapshot.total))
        assertTrue(webTaskOrderId.isNotEmpty())

        val exported = export("android-chain1-continued.dgbk", null)
        writeCanonical(exported, "android-chain1-continued-canonical.json")
        writeProvenance(
            "android-chain1-continued-provenance.json",
            "b3RestoreChain1ContinuationAndExport",
            "Android production restore of the browser continuation export, one more absolute total, production re-export",
            listOf(Triple("android-chain1-continued.dgbk", exported, null)),
            listOf(
                Pair(
                    "android-chain1-continued-canonical.json",
                    File(outDir, "android-chain1-continued-canonical.json"),
                ),
            ),
            buildJsonObject {
                put("input", CHAIN1_CONTINUED_INPUT)
                put("inputSha256", sha256(source.readBytes()))
                put("continuedTaskId", webTaskId)
                put("continuedTotal", "25")
            },
        )
    }

    /** Chain-1 no-edit echo: restore the browser's no-edit export and re-export untouched. */
    @Test
    fun b4NoEditEchoChain1() = runBlocking<Unit> {
        val source = inputFile(CHAIN1_NOEDIT_INPUT)
        restore(source, null)
        val exported = export("android-chain1-noedit-echo.dgbk", null)
        writeCanonical(exported, "android-chain1-noedit-echo-canonical.json")
        writeProvenance(
            "android-chain1-noedit-echo-provenance.json",
            "b4NoEditEchoChain1",
            "Android production restore + immediate re-export (no business edits)",
            listOf(Triple("android-chain1-noedit-echo.dgbk", exported, null)),
            listOf(
                Pair(
                    "android-chain1-noedit-echo-canonical.json",
                    File(outDir, "android-chain1-noedit-echo-canonical.json"),
                ),
            ),
            buildJsonObject {
                put("input", CHAIN1_NOEDIT_INPUT)
                put("inputSha256", sha256(source.readBytes()))
            },
        )
    }

    /** Chain-2 no-edit echo: browser-native dataset restored and re-exported untouched. */
    @Test
    fun b5NoEditEchoChain2() = runBlocking<Unit> {
        val source = inputFile(PLAIN_INPUT)
        restore(source, null)
        val exported = export("android-chain2-noedit-echo.dgbk", null)
        writeCanonical(exported, "android-chain2-noedit-echo-canonical.json")
        writeProvenance(
            "android-chain2-noedit-echo-provenance.json",
            "b5NoEditEchoChain2",
            "Android production restore + immediate re-export of the browser-native plain export (no business edits)",
            listOf(Triple("android-chain2-noedit-echo.dgbk", exported, null)),
            listOf(
                Pair(
                    "android-chain2-noedit-echo-canonical.json",
                    File(outDir, "android-chain2-noedit-echo-canonical.json"),
                ),
            ),
            buildJsonObject {
                put("input", PLAIN_INPUT)
                put("inputSha256", sha256(source.readBytes()))
            },
        )
    }

    /**
     * Legacy receiver: a structurally valid **schema-14** file restores into the current
     * schema without losing old data, and re-exports as schema 15.
     */
    @Test
    fun b6RestoreSyntheticSchema14AndExport() = runBlocking<Unit> {
        val source = inputFile(LEGACY14_INPUT)
        val inspection = restoreService().inspect(Uri.fromFile(source))
        assertFalse(inspection.encrypted)
        val preview = restore(source, null)
        assertEquals("legacy file declares schema 14", 14, preview.schemaVersion)

        val data = access.dump()
        assertEquals(1, data.rows("owners").size)
        assertEquals(1, data.rows("game_accounts").size)
        assertEquals(2, data.rows("orders").size)

        // The schema-14 count order keeps its order-level target, and the normalizer
        // backfills the period target and every round's owner.
        val orderSpec = checkNotNull(BackupTableCatalog.table("orders"))
        val countOrderRow = data.rows("orders").single { row ->
            (row.getOrNull(orderSpec.columnNames.indexOf("hostingMode")) as? JsonPrimitive)?.content ==
                HostingMode.COUNT_BASED.name
        }
        assertEquals(
            3,
            (countOrderRow.getOrNull(orderSpec.columnNames.indexOf("targetSessions")) as? JsonPrimitive)
                ?.content?.toInt(),
        )
        val periodSpec = checkNotNull(BackupTableCatalog.table("hosting_periods"))
        val countPeriod = data.rows("hosting_periods").first { row ->
            (row.getOrNull(periodSpec.columnNames.indexOf("orderId")) as? JsonPrimitive)?.content ==
                (countOrderRow.getOrNull(orderSpec.columnNames.indexOf("id")) as? JsonPrimitive)?.content
        }
        assertEquals(
            "period target backfilled from the order target",
            3,
            (countPeriod.getOrNull(periodSpec.columnNames.indexOf("serviceCount")) as? JsonPrimitive)
                ?.content?.toInt(),
        )
        val sessionSpec = checkNotNull(BackupTableCatalog.table("hosting_sessions"))
        assertEquals(2, data.rows("hosting_sessions").size)
        data.rows("hosting_sessions").forEach { row ->
            assertNotNull(
                "round ownership backfilled",
                (row.getOrNull(sessionSpec.columnNames.indexOf("periodId")) as? JsonPrimitive)?.content,
            )
        }
        // No invented history for legacy input.
        assertTrue(data.rows("account_categories").isEmpty())
        assertTrue(data.rows("task_progress_events").isEmpty())
        // The old receipt survives untouched.
        assertEquals(1, data.rows("receipts").size)

        val exported = export("android-legacy14-restored.dgbk", null)
        writeCanonical(exported, "android-legacy14-restored-canonical.json")
        val reRead = BackupArchiveReader.read(
            exported,
            File(caseDir, "legacy-upgraded").apply { mkdirs() },
            RECORD_DATABASE_VERSION,
        )
        assertEquals("re-export declares the current portable schema", 15, reRead.manifest.schemaVersion)
        writeProvenance(
            "android-legacy14-restored-provenance.json",
            "b6RestoreSyntheticSchema14AndExport",
            "Synthetic structural schema-14 fixture restored into Room 15 and re-exported as portable 15",
            listOf(Triple("android-legacy14-restored.dgbk", exported, null)),
            listOf(
                Pair(
                    "android-legacy14-restored-canonical.json",
                    File(outDir, "android-legacy14-restored-canonical.json"),
                ),
            ),
            buildJsonObject {
                put("input", LEGACY14_INPUT)
                put("inputSha256", sha256(source.readBytes()))
                put("fixtureKind", "synthetic-historical-schema14")
                put("migration", "normalizer backfills count period serviceCount and session periodId")
            },
        )
    }

    // ----------------------------------------------------------------- adverse

    /**
     * Adverse matrix. Every case must be rejected at the restore boundary and leave the
     * active dataset and the source bytes untouched (no partial replacement).
     */
    @Test
    fun c1AdverseCasesPreserveTheActiveDataset() = runBlocking<Unit> {
        env.ownerRepository.createOwner("存活老板")
        val liveBefore = access.dump()
        val liveOwnerCount = liveBefore.rows("owners").size
        val plainSource = inputFile(PLAIN_INPUT)
        val sourceBytes = plainSource.readBytes()

        val report = mutableListOf<JsonObject>()

        suspend fun attempt(case: String, file: File, password: CharArray?, expectMessage: String?) {
            val before = sha256(file.readBytes())
            val service = restoreService()
            var rejected = false
            var message = ""
            try {
                service.inspect(Uri.fromFile(file))
                service.prepare(password)
                service.applyConfirmed()
            } catch (error: Throwable) {
                rejected = true
                message = error.message.orEmpty()
            }
            val live = access.dump()
            assertEquals("$case: active owner count", liveOwnerCount, live.rows("owners").size)
            assertTrue("$case: live owner preserved", live.rows("owners").any { row ->
                val spec = checkNotNull(BackupTableCatalog.table("owners"))
                (row.getOrNull(spec.columnNames.indexOf("nickname")) as? JsonPrimitive)?.content == "存活老板"
            })
            assertEquals("$case: source bytes unchanged", before, sha256(file.readBytes()))
            assertFalse("$case: no journal left behind", storage.hasJournalFile())
            if (expectMessage != null) {
                assertTrue("$case rejected", rejected)
                assertTrue("$case message: $message", message.contains(expectMessage))
            }
            report += buildJsonObject {
                put("case", case)
                put("rejected", rejected)
                put("message", message.take(400))
                put("expectedMessageFragment", expectMessage ?: "")
                put("liveDatasetPreserved", true)
                put("sourceBytesUnchanged", true)
                put("platform", "android-emulator")
            }
        }

        // 1. Wrong ASCII password on a real browser-encrypted file.
        attempt("wrong-password", inputFile(ENCRYPTED_INPUT), "wrong-password".toCharArray(), null)
        // 2. Wrong Unicode password on the Android-produced encrypted fixture.
        attempt("wrong-password-unicode", inputFile(ANDROID_ENCRYPTED), "口令不对".toCharArray(), "密码")

        // 3. Tampered encrypted content (one byte flipped in the ciphertext body).
        val tampered = File(caseDir, "tampered-encrypted.dgbk")
        val encryptedBytes = inputFile(ENCRYPTED_INPUT).readBytes()
        encryptedBytes[encryptedBytes.size / 2] =
            (encryptedBytes[encryptedBytes.size / 2].toInt() xor 0x5A).toByte()
        tampered.writeBytes(encryptedBytes)
        attempt("tampered-encrypted", tampered, UNICODE_PASSWORD.toCharArray(), null)

        // 4. Missing attachment: the manifest still declares it, the archive does not carry it.
        val stripped = File(caseDir, "missing-attachment.dgbk")
        withArchiveFrom(plainSource, stripped) { manifest, dataBytes, _ ->
            Triple(manifest, dataBytes, emptyList())
        }
        attempt("missing-attachment", stripped, null, null)

        // 5. Tampered attachment bytes (same length, different content).
        val tamperedAttachment = File(caseDir, "tampered-attachment.dgbk")
        withArchiveFrom(plainSource, tamperedAttachment) { manifest, dataBytes, attachments ->
            val changed = attachments.map { (path, file) ->
                val bytes = file.readBytes()
                bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
                val replacement = File(caseDir, "tampered-${file.name}").apply { writeBytes(bytes) }
                path to replacement
            }
            Triple(manifest, dataBytes, changed)
        }
        attempt("tampered-attachment", tamperedAttachment, null, null)

        // 6. Unsupported portable schema (declared future version).
        val future = File(caseDir, "future-schema.dgbk")
        withArchiveFrom(plainSource, future) { manifest, dataBytes, attachments ->
            Triple(manifest.copy(schemaVersion = 16), dataBytes, attachments)
        }
        attempt("unsupported-schema-16", future, null, null)

        // 7. Invalid session ownership: a SESSION event points at a round of another order.
        val badOwnership = File(caseDir, "bad-session-ownership.dgbk")
        patchRowsInArchive(plainSource, badOwnership, "task_progress_events") { rows, spec ->
            rows.map { row ->
                val scopeTypeIndex = spec.columnNames.indexOf("scopeType")
                if ((row.getOrNull(scopeTypeIndex) as? JsonPrimitive)?.content == "SESSION") {
                    row.toMutableList().also {
                        it[spec.columnNames.indexOf("scopeSessionId")] =
                            JsonPrimitive("session-of-another-order")
                    }
                } else {
                    row
                }
            }
        }
        attempt("invalid-session-ownership", badOwnership, null, null)

        // 8/9 need an archive that actually carries a completion batch: the chain-1
        // continuation export does (the browser restored the Android short-order batch and
        // kept it). Guarding on a non-empty item list prevents a silent no-op patch.
        val batchSource = inputFile(CHAIN1_CONTINUED_INPUT)

        // 8. Invalid batch reference: a SHORT_TASK item points at a task that does not
        //    exist in the archive (the validator's task-existence rule; case 9 covers the
        //    OCCURRENCE branch and CD-01 separately, so the two boundaries stay distinct).
        val badBatch = File(caseDir, "bad-batch-reference.dgbk")
        patchRowsInArchive(batchSource, badBatch, "completion_batch_items") { rows, spec ->
            val shortItems = rows.filter {
                (it.getOrNull(spec.columnNames.indexOf("itemType")) as? JsonPrimitive)?.content == "SHORT_TASK"
            }
            check(shortItems.isNotEmpty()) { "the fixture must carry a SHORT_TASK batch item" }
            rows.map { row ->
                val isShort =
                    (row.getOrNull(spec.columnNames.indexOf("itemType")) as? JsonPrimitive)?.content == "SHORT_TASK"
                if (!isShort) {
                    row
                } else {
                    row.toMutableList().also {
                        it[spec.columnNames.indexOf("taskDefinitionId")] = JsonPrimitive("task-of-nothing")
                    }
                }
            }
        }
        attempt("invalid-batch-reference", badBatch, null, null)

        // 9. CD-01: the fixture's short-order batch is already undone, so a non-null but
        //    missing target is exactly the unsupported class: rejected before activation
        //    with an object-level diagnostic that keeps the original id.
        val danglingUndone = File(caseDir, "undone-dangling.dgbk")
        patchRowsInArchive(batchSource, danglingUndone, "completion_batch_items") { rows, spec ->
            val occurrenceItems = rows.filter {
                (it.getOrNull(spec.columnNames.indexOf("itemType")) as? JsonPrimitive)?.content == "OCCURRENCE"
            }
            check(occurrenceItems.isNotEmpty()) {
                "the fixture must carry an OCCURRENCE batch item (undone bulk completion)"
            }
            rows.map { row ->
                val isOccurrence =
                    (row.getOrNull(spec.columnNames.indexOf("itemType")) as? JsonPrimitive)?.content == "OCCURRENCE"
                if (!isOccurrence) {
                    row
                } else {
                    row.toMutableList().also {
                        it[spec.columnNames.indexOf("occurrenceId")] =
                            JsonPrimitive("deleted-occurrence-original-123")
                    }
                }
            }
        }
        attempt("cd01-undone-dangling-target", danglingUndone, null, "UNDONE_BATCH_TARGET_DANGLING")

        // 10. Write failure before activation (injected at the file-swap checkpoint).
        val service = restoreService()
        service.inspect(Uri.fromFile(plainSource))
        service.prepare(null)
        service.transitionHook = { if (it == "FILES_SWAPPED") throw java.io.IOException("injected swap failure") }
        var injected = false
        try {
            service.applyConfirmed()
        } catch (_: java.io.IOException) {
            injected = true
        }
        assertTrue("injected failure reached", injected)
        service.transitionHook = null
        assertEquals("write failure keeps the live dataset", liveOwnerCount, access.dump().rows("owners").size)
        assertFalse(storage.hasJournalFile())
        report += buildJsonObject {
            put("case", "write-failure-before-activation")
            put("rejected", true)
            put("message", "injected IOException at FILES_SWAPPED")
            put("liveDatasetPreserved", true)
            put("sourceBytesUnchanged", true)
            put("platform", "android-emulator")
        }

        // 11. Stale pending command after a dataset replacement: the generation captured
        //     before the restore must be rejected afterwards, with no rows written.
        val generationBefore = DatasetGate.currentGeneration()
        restore(plainSource, null)
        assertNotEquals(generationBefore, DatasetGate.currentGeneration())
        var staleRejected = false
        var staleMessage = ""
        try {
            progress.submitTotal(
                SubmitTotalCommand(
                    operationId = UUID.randomUUID().toString(),
                    taskId = "task-from-before",
                    scope = ProgressScope.Task,
                    attributedGameDate = day1,
                    total = BigDecimal("1"),
                    expectedGeneration = generationBefore,
                ),
            )
        } catch (error: Throwable) {
            staleRejected = true
            staleMessage = error.message.orEmpty()
        }
        assertTrue("stale generation rejected: $staleMessage", staleRejected)
        assertEquals(0, env.database.taskProgressEventDao().countForTask("task-from-before"))
        report += buildJsonObject {
            put("case", "stale-command-after-replacement")
            put("rejected", true)
            put("message", staleMessage.take(300))
            put("liveDatasetPreserved", true)
            put("sourceBytesUnchanged", true)
            put("platform", "android-emulator")
        }

        File(outDir, "android-adverse-report.json").writeText(
            Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(report)),
            Charsets.UTF_8,
        )
        assertTrue("every adverse case recorded", report.size >= 11)
        assertTrue(sourceBytes.isNotEmpty())
    }

    // ------------------------------------------------------------- assertions

    private suspend fun verifyRestoredWebDataset() {
        val data = access.dump()
        assertEquals(2, data.rows("owners").size)
        val accountSpec = checkNotNull(BackupTableCatalog.table("game_accounts"))
        val accounts = data.rows("game_accounts")
        assertEquals(2, accounts.size)
        accountSpec.columnNames.let { }
        accounts.forEach { row ->
            assertNotNull((row.getOrNull(accountSpec.columnNames.indexOf("id")) as? JsonPrimitive)?.content)
        }
        assertTrue(data.rows("account_categories").size >= 2)
        val templateSpec = checkNotNull(BackupTableCatalog.table("game_templates"))
        val templateTitles = data.rows("game_templates").mapNotNull { row ->
            (row.getOrNull(templateSpec.columnNames.indexOf("defaultOrderTitle")) as? JsonPrimitive)?.content
        }
        assertTrue(
            "UF-036 default title survives: $templateTitles",
            templateTitles.contains("网页默认标题"),
        )
        assertTrue("restored cumulative events: ${data.rows("task_progress_events").size}", data.rows("task_progress_events").size >= 2)
        assertTrue("restored rounds", data.rows("hosting_sessions").isNotEmpty())

        // UF-037: identical visible account identities under two owners stay distinct.
        val uidIndex = accountSpec.columnNames.indexOf("uid")
        val ownerIndex = accountSpec.columnNames.indexOf("ownerId")
        val duplicateUidGroups = accounts
            .groupBy { (it.getOrNull(uidIndex) as? JsonPrimitive)?.content }
            .filterValues { rows -> rows.size > 1 }
        assertTrue("cross-owner duplicate visible identity is in the fixture", duplicateUidGroups.isNotEmpty())
        duplicateUidGroups.values.forEach { rows ->
            assertEquals(2, rows.map { (it.getOrNull(ownerIndex) as? JsonPrimitive)?.content }.distinct().size)
        }

        // UF-038 through the production query path plus the real ViewModel read model.
        val taskSpec = checkNotNull(BackupTableCatalog.table("task_definitions"))
        val cumulativeRow = data.rows("task_definitions").first { row ->
            (row.getOrNull(taskSpec.columnNames.indexOf("numericMode")) as? JsonPrimitive)?.content ==
                NumericMode.CUMULATIVE.name
        }
        val taskId = (cumulativeRow.getOrNull(taskSpec.columnNames.indexOf("id")) as? JsonPrimitive)!!.content
        val orderId = (cumulativeRow.getOrNull(taskSpec.columnNames.indexOf("orderId")) as? JsonPrimitive)!!.content
        val status = checkNotNull(progress.cumulativeStatus(taskId, scopeOf(taskId))) {
            "a restored cumulative task must resolve through the production query"
        }
        assertTrue("restored total is usable", status.total >= BigDecimal.ZERO)

        val viewModel = OrderDetailViewModel(
            SavedStateHandle(mapOf(Routes.ORDER_ID_ARG to orderId)),
            env.orderRepository,
            env.attachmentRepository,
            env.receiptRepository,
            env.clock,
            progress,
        )
        val ui = withTimeout(15_000) { viewModel.uiState.first { !it.loading && it.detail != null } }
        val detail = checkNotNull(ui.detail) { "the production ViewModel must render the restored order" }
        val periodId = checkNotNull(env.database.orderDao().findPeriods(orderId).firstOrNull()).id
        assertTrue("restored order detail lists tasks", detail.tasksForPeriod(periodId).isNotEmpty())

        // A restored attachment must be readable real bytes.
        val attachmentSpec = checkNotNull(BackupTableCatalog.table("attachments"))
        val attachmentRow = data.rows("attachments").firstOrNull()
        assertNotNull("restored attachment row", attachmentRow)
        val relativePath = (attachmentRow!!
            .getOrNull(attachmentSpec.columnNames.indexOf("relativePath")) as? JsonPrimitive)!!.content
        val restoredFile = File(storage.attachmentsDirectory, relativePath)
        assertTrue("attachment file exists after restore", restoredFile.isFile)
        val bytes = restoredFile.readBytes()
        assertTrue(
            "restored attachment is a real JPEG (not a marker)",
            bytes.size > 512 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte(),
        )
        assertEquals(
            "attachment size matches the row",
            (attachmentRow.getOrNull(attachmentSpec.columnNames.indexOf("sizeBytes")) as? JsonPrimitive)!!.content.toLong(),
            bytes.size.toLong(),
        )
    }

    /** Continue working on Android after a restore, then export through production code. */
    private suspend fun continueOnAndroid(outputName: String, generator: String) {
        val data = access.dump()
        val taskSpec = checkNotNull(BackupTableCatalog.table("task_definitions"))
        val cumulativeRow = data.rows("task_definitions").first { row ->
            (row.getOrNull(taskSpec.columnNames.indexOf("numericMode")) as? JsonPrimitive)?.content ==
                NumericMode.CUMULATIVE.name
        }
        val taskId = (cumulativeRow.getOrNull(taskSpec.columnNames.indexOf("id")) as? JsonPrimitive)!!.content
        val orderId = (cumulativeRow.getOrNull(taskSpec.columnNames.indexOf("orderId")) as? JsonPrimitive)!!.content
        val scope = scopeOf(taskId)

        // Continue on the next game day so the write window is explicit and legal.
        env.clock.set(Instant.parse("2026-03-11T02:00:00Z"))
        // 1. UF-038 absolute total: 10 then 15 stays 15 (never 25).
        val first = submit(taskId, scope, day2, "10")
        assertEquals(0, BigDecimal("10").compareTo(first.snapshot.total))
        val second = submit(taskId, scope, day2, "15")
        assertEquals("absolute total is replaced, not accumulated", 0, BigDecimal("15").compareTo(second.snapshot.total))
        // 2. Same operationId replays without adding an event; a new operation adds one.
        val replay = progress.submitTotal(
            SubmitTotalCommand(
                operationId = second.event.operationId,
                taskId = taskId,
                scope = scope,
                attributedGameDate = day2,
                total = BigDecimal("15"),
            ),
        )
        assertTrue("replay is idempotent", replay is ProgressCommandResult.IdempotentReplay)
        val beforeRetry = env.database.taskProgressEventDao().findForTask(taskId).size
        val retry = progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = taskId,
                scope = scope,
                attributedGameDate = day2,
                total = BigDecimal("15"),
            ),
        )
        assertTrue(retry is ProgressCommandResult.Applied)
        assertEquals(
            "a new operation writes exactly one row",
            beforeRetry + 1,
            env.database.taskProgressEventDao().findForTask(taskId).size,
        )
        // 3. Correct an earlier observation; the later effective total must survive.
        val earliest = progress.progressHistory(taskId).first { it.kind.name == "SUBMIT" }
        progress.correctTotal(
            CorrectTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = taskId,
                targetEventId = earliest.id,
                total = BigDecimal("8"),
            ),
        )
        assertEquals(
            "an earlier correction never overwrites a later absolute total",
            0,
            BigDecimal("15").compareTo(checkNotNull(progress.cumulativeStatus(taskId, scope)).total),
        )

        // 4. UF-037: rename a category, reassign an account, delete a category.
        val categorySpec = checkNotNull(BackupTableCatalog.table("account_categories"))
        val categoryIds = access.dump().rows("account_categories")
            .mapNotNull { (it.getOrNull(categorySpec.columnNames.indexOf("id")) as? JsonPrimitive)?.content }
        val categoryId = categoryIds.first()
        categoryRepository.renameCategory(categoryId, "安卓改名分类")
        assertEquals("安卓改名分类", categoryRepository.findCategories().first { it.id == categoryId }.name)
        val newCategory = categoryRepository.createCategory("安卓新建分类")
        val accountSpec = checkNotNull(BackupTableCatalog.table("game_accounts"))
        val accountId = access.dump().rows("game_accounts")
            .mapNotNull { (it.getOrNull(accountSpec.columnNames.indexOf("id")) as? JsonPrimitive)?.content }
            .first()
        categoryRepository.assignAccountCategory(accountId, newCategory.id)
        assertEquals(newCategory.id, env.database.gameAccountDao().findById(accountId)?.categoryId)
        val accountsBefore = access.dump().rows("game_accounts").size
        val ordersBefore = access.dump().rows("orders").size
        categoryRepository.deleteCategory(categoryId)
        assertEquals("deleting a category keeps accounts", accountsBefore, access.dump().rows("game_accounts").size)
        assertEquals("deleting a category keeps orders", ordersBefore, access.dump().rows("orders").size)
        assertNotEquals(
            "the account does not point at the deleted category",
            categoryId,
            env.database.gameAccountDao().findById(accountId)?.categoryId,
        )

        // 5. A receipt amendment (audit chain keeps the previous values).
        val receiptSpec = checkNotNull(BackupTableCatalog.table("receipts"))
        val receiptId = (access.dump().rows("receipts").firstOrNull()
            ?.getOrNull(receiptSpec.columnNames.indexOf("id")) as? JsonPrimitive)?.content
        if (receiptId != null) {
            val receipt = checkNotNull(env.database.receiptDao().findById(receiptId))
            env.receiptRepository.correctReceipt(
                ReceiptCorrectionRequest.Amend(
                    operationId = UUID.randomUUID().toString(),
                    receiptId = receiptId,
                    amountCents = (receipt.amountCents - 100).coerceAtLeast(0),
                    receivedAt = env.clock.now(),
                    channel = PaymentChannel.WECHAT,
                    note = "安卓端更正",
                ),
            )
        }

        val exported = export(outputName, null)
        val canonicalName = outputName.removeSuffix(".dgbk") + "-canonical.json"
        writeCanonical(exported, canonicalName)
        writeProvenance(
            outputName.removeSuffix(".dgbk") + "-provenance.json",
            generator,
            "Android production restore followed by real business commands, then production export",
            listOf(Triple(outputName, exported, null)),
            listOf(Pair(canonicalName, File(outDir, canonicalName))),
            buildJsonObject {
                put("continuedTaskId", taskId)
                put("continuedOrderId", orderId)
                put("absoluteTotals", "10 then 15 -> 15")
                put("earlierCorrection", "8 on the first observation; later total still 15")
                put("categoryRenameAndDelete", true)
                put("receiptAmendment", receiptId != null)
            },
        )
    }

    private suspend fun scopeOf(taskId: String): ProgressScope {
        val data = access.dump()
        val taskSpec = checkNotNull(BackupTableCatalog.table("task_definitions"))
        val row = data.rows("task_definitions").first { candidate ->
            (candidate.getOrNull(taskSpec.columnNames.indexOf("id")) as? JsonPrimitive)?.content == taskId
        }
        val orderId = (row.getOrNull(taskSpec.columnNames.indexOf("orderId")) as? JsonPrimitive)!!.content
        val periodId = (row.getOrNull(taskSpec.columnNames.indexOf("periodId")) as? JsonPrimitive)?.content
        val order = checkNotNull(env.database.orderDao().findById(orderId))
        return when {
            order.type == OrderType.SHORT_TASK -> ProgressScope.Task
            order.hostingMode == HostingMode.COUNT_BASED -> when (val state = progress.resolveActiveCountSession(orderId)) {
                is CountHosting.ActiveCountState.ActiveSession -> ProgressScope.Session(state.sessionId)
                is CountHosting.ActiveCountState.AwaitingSessionRow ->
                    ProgressScope.Session(progress.ensureCountSession(orderId).session.id)
                else -> ProgressScope.Session(
                    checkNotNull(env.database.hostingSessionDao().findForOrder(orderId).firstOrNull()).id,
                )
            }
            else -> ProgressScope.Period(checkNotNull(periodId))
        }
    }

    private suspend fun submit(
        taskId: String,
        scope: ProgressScope,
        gameDate: LocalDate,
        total: String,
    ): ProgressCommandResult.Applied {
        val result = progress.submitTotal(
            SubmitTotalCommand(
                operationId = UUID.randomUUID().toString(),
                taskId = taskId,
                scope = scope,
                attributedGameDate = gameDate,
                total = BigDecimal(total),
            ),
        )
        return result as? ProgressCommandResult.Applied
            ?: error("expected an applied command, got ${result::class.simpleName}")
    }

    // ------------------------------------------------------------ production IO

    private fun fileDocumentStore(target: File) = object : BackupDocumentStore {
        override suspend fun copyDocumentToFile(uri: Uri, target: File, maxBytes: Long): Long {
            val file = File(checkNotNull(uri.path))
            check(file.length() <= maxBytes) { "input too large" }
            file.copyTo(target, overwrite = true)
            return file.length()
        }

        override suspend fun writeFileToDocument(uri: Uri, source: File) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }

        override fun displayName(uri: Uri): String = target.name

        override fun hasRoomFor(bytes: Long): Boolean = true
    }

    private fun reminderCoordinator(): ReminderCoordinator {
        val scheduler = object : ReminderScheduler {
            override suspend fun scheduleAll(settings: ReminderSettings, now: Instant) = Unit
            override suspend fun scheduleCategory(category: ReminderCategory, minutes: Int, now: Instant) = Unit
            override suspend fun cancel(category: ReminderCategory) = Unit
            override suspend fun cancelAll() = Unit
        }
        val notifier = object : ReminderNotifier {
            override fun canNotify() = false
            override fun post(message: ReminderMessage) = false
            override fun notificationSettingsIntent() = Intent()
        }
        return ReminderCoordinator(
            DefaultReminderSettingsRepository(env.database.reminderSettingsDao(), env.clock),
            ReminderRepository(
                env.database,
                env.database.orderDao(),
                env.database.gameAccountDao(),
                env.database.ownerDao(),
            ),
            scheduler,
            notifier,
            env.clock,
            ReportingTimeZoneProvider { ZoneId.of("Asia/Shanghai") },
        )
    }

    private fun restoreService(): BackupRestoreService = BackupRestoreService(
        storage = storage,
        databaseAccess = access,
        snapshotBuilder = builder,
        validator = BackupValidator(),
        attachmentSync = env.attachmentSync,
        documentStore = fileDocumentStore(File(caseDir, "unused-target")),
        timeProvider = env.clock,
        reminderCoordinator = reminderCoordinator(),
    )

    private suspend fun restore(source: File, password: CharArray?): RestorePreview {
        val service = restoreService()
        service.inspect(Uri.fromFile(source))
        val preview = service.prepare(password)
        service.applyConfirmed()
        return preview
    }

    private fun export(name: String, password: CharArray?): File {
        val target = File(outDir, name)
        val service = BackupExportService(
            storage = storage,
            snapshotBuilder = builder,
            attachmentSync = env.attachmentSync,
            documentStore = fileDocumentStore(target),
            timeProvider = env.clock,
        )
        runBlocking { service.export(Uri.EMPTY, password?.copyOf()) }
        return target
    }

    private fun inputFile(name: String): File {
        val file = File(inDir, name)
        assertTrue("host must push $name into ${inDir.path}", file.isFile)
        return file
    }

    // --------------------------------------------------------------- canonical

    /**
     * Canonical business snapshot of a produced archive, read back through the production
     * archive reader + data codec. Rows keep the catalog column order; the host-side
     * comparator re-sorts by primary key and compares decimal text canonically.
     */
    private suspend fun writeCanonical(archive: File, name: String) {
        val extractDir = File(caseDir, "canonical-${archive.name.hashCode()}").apply {
            deleteRecursively()
            mkdirs()
        }
        val extracted = BackupArchiveReader.read(archive, extractDir, RECORD_DATABASE_VERSION)
        val dataSet = BackupDataCodec.decode(
            extracted.dataBytes.toString(Charsets.UTF_8),
            extracted.manifest.schemaVersion,
        )
        val tables = buildJsonObject {
            for (table in dataSet.tables) {
                putJsonArray(table.table.name) {
                    for (row in table.rows) add(rowToJson(table.table, row))
                }
            }
        }
        val digests = buildJsonArray {
            val attachmentSpec = checkNotNull(BackupTableCatalog.table("attachments"))
            val declaredById = extracted.manifest.attachments.associateBy { it.id }
            for (row in dataSet.rows("attachments")) {
                val id = (row.getOrNull(attachmentSpec.columnNames.indexOf("id")) as? JsonPrimitive)?.content ?: continue
                val relativePath = (row.getOrNull(attachmentSpec.columnNames.indexOf("relativePath")) as? JsonPrimitive)?.content
                val bytes = relativePath?.let { File(extracted.attachmentsDirectory, it).takeIf { file -> file.isFile }?.readBytes() }
                add(buildJsonObject {
                    put("id", id)
                    put("relativePath", relativePath)
                    put("mimeType", (row.getOrNull(attachmentSpec.columnNames.indexOf("mimeType")) as? JsonPrimitive)?.content)
                    put("sizeBytes", bytes?.size ?: declaredById[id]?.sizeBytes ?: 0)
                    put("sha256", bytes?.let(::sha256) ?: "")
                })
            }
        }
        val canonical = buildJsonObject {
            put("portableSchema", extracted.manifest.schemaVersion)
            put("templateFormat", 4)
            put("exportedAt", extracted.manifest.exportedAt)
            put("tables", tables)
            put("attachmentDigests", digests)
        }
        File(outDir, name).writeText(
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), canonical),
            Charsets.UTF_8,
        )
    }

    private fun rowToJson(table: BackupTableCatalog.Table, row: List<JsonElement?>): JsonObject =
        buildJsonObject {
            for (column in table.writtenColumnNames) {
                // `row` is indexed by the catalog's full column list (legacy-only columns
                // included), so the position must be looked up by name, not by the written
                // column's ordinal.
                val element = row.getOrNull(table.columnNames.indexOf(column))
                when {
                    element == null || element is JsonNull -> put(column, JsonNull)
                    table.column(column)?.type == BackupTableCatalog.ColumnType.INTEGER -> {
                        val parsed = (element as JsonPrimitive).content.toLongOrNull()
                        if (parsed == null) put(column, JsonNull) else put(column, parsed)
                    }
                    else -> put(column, (element as JsonPrimitive).content)
                }
            }
        }

    // -------------------------------------------------------------- provenance

    private suspend fun writeProvenance(
        file: String,
        generator: String,
        description: String,
        archives: List<Triple<String, File, String?>>,
        extras: List<Pair<String, File>>,
        extraJson: JsonObject,
    ) {
        val rowCounts = buildJsonObject {
            for (table in access.dump().tables) put(table.table.name, table.rows.size)
        }
        val provenance = buildJsonObject {
            put("generatedBy", "com.daigan.record.backup.Stage5ExchangeTest#$generator")
            put("description", description)
            put("appVersionName", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("roomSchema", RECORD_DATABASE_VERSION)
            put("portableSchema", 15)
            put("templateFormat", 4)
            put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            put("apiLevel", android.os.Build.VERSION.SDK_INT)
            put("hardware", "emulator")
            put("watermark", "emulator-not-physical-device")
            put("generatedAt", env.clock.now().toString())
            put("syntheticDataOnly", true)
            put("attachmentKind", "real-jpeg-produced-on-device")
            put("extra", extraJson)
            putJsonArray("files") {
                archives.forEach { (name, archive, password) ->
                    add(buildJsonObject {
                        put("file", name)
                        put("sha256", sha256(archive.readBytes()))
                        put("sizeBytes", archive.length())
                        put("encrypted", password != null)
                        if (password != null) put("syntheticPassword", password)
                    })
                }
                extras.forEach { (name, extra) ->
                    add(buildJsonObject {
                        put("file", name)
                        put("sha256", sha256(extra.readBytes()))
                        put("sizeBytes", extra.length())
                    })
                }
            }
            put("rowCounts", rowCounts)
            putJsonArray("limitations") {
                add(JsonPrimitive("Emulator only: no physical device participated."))
                add(JsonPrimitive("Synthetic data; no real user records or secrets."))
                add(JsonPrimitive("exportedAt / ZIP timestamps change per run, so archive hashes differ between runs."))
            }
        }
        File(outDir, file).writeText(
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), provenance),
            Charsets.UTF_8,
        )
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Re-writes an archive with the production writer, letting the caller change the
     * manifest, the data bytes or the attachment files. Used only to construct *invalid*
     * inputs (negative cases) and the structural legacy fixture.
     */
    private suspend fun withArchiveFrom(
        source: File,
        target: File,
        transform: (BackupManifest, ByteArray, List<Pair<String, File>>) -> Triple<BackupManifest, ByteArray, List<Pair<String, File>>>,
    ) {
        val extractDir = File(caseDir, "mutate-${System.nanoTime()}").apply { mkdirs() }
        val extracted = BackupArchiveReader.read(source, extractDir, RECORD_DATABASE_VERSION)
        val attachments = extracted.attachmentsDirectory.listFiles().orEmpty()
            .sortedBy { it.name }
            .map { it.name to it }
        val (manifest, dataBytes, changed) = transform(extracted.manifest, extracted.dataBytes, attachments)
        FileOutputStream(target).use { output ->
            BackupArchiveWriter.write(
                output,
                BackupManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8),
                dataBytes,
                changed,
                env.clock.now().toEpochMilli(),
            )
        }
    }

    private suspend fun patchRowsInArchive(
        source: File,
        target: File,
        table: String,
        transform: (List<List<JsonElement?>>, BackupTableCatalog.Table) -> List<List<JsonElement?>>,
    ) {
        withArchiveFrom(source, target) { manifest, dataBytes, attachments ->
            val dataSet = BackupDataCodec.decode(dataBytes.toString(Charsets.UTF_8), manifest.schemaVersion)
            val spec = checkNotNull(BackupTableCatalog.table(table))
            val patched = BackupDataSet(
                dataSet.tables.map { tableRows ->
                    if (tableRows.table.name != table) {
                        tableRows
                    } else {
                        tableRows.copy(rows = transform(tableRows.rows, spec))
                    }
                },
            )
            val encoded = BackupDataCodec.encode(patched).toByteArray(Charsets.UTF_8)
            Triple(
                manifest.copy(
                    dataSha256 = Sha256.of(encoded),
                    tableCounts = patched.tables.associate { it.table.name to it.rows.size },
                ),
                encoded,
                attachments,
            )
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hexOf(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
}
