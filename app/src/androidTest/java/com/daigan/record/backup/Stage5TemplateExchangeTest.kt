package com.daigan.record.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daigan.record.data.local.RECORD_DATABASE_VERSION
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TemplateCategoryDraft
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.data.model.TemplatePricePresetDraft
import com.daigan.record.data.model.TemplateTaskDraft
import com.daigan.record.data.template.ContentResolverTemplateFileRepository
import com.daigan.record.data.template.TemplateFileCodec
import com.daigan.record.data.template.TemplateFileResult
import com.daigan.record.feature.orders.NewOrderViewModel
import com.daigan.record.feature.templates.TemplateLibraryViewModel
import com.daigan.record.testing.OrderTestEnvironment
import com.daigan.record.ui.navigation.Routes
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 5 — independent **template format 4** exchange (not a full-backup restore).
 *
 * Group `a*` authors one rich Android template through the production repository and writes
 * it with the production codec + the real document stream (`TemplateLibraryViewModel.exportTo`
 * over `ContentResolverTemplateFileRepository`).
 * Group `b*` consumes a **web-produced** template file through the real picker stream and the
 * production import/resolution path, verifies what was persisted, creates an order from the
 * imported template with the production `NewOrderViewModel`, and exports it again.
 *
 * Files cross the platform boundary through
 * `Android/data/com.daigan.record/files/interop-stage5-template/{in,out}`, which the host
 * fills and pulls with adb. Every fixture is synthetic; no user data is involved.
 */
@RunWith(AndroidJUnit4::class)
class Stage5TemplateExchangeTest {

    companion object {
        /** Android-authored format-4 template, consumed by the browser (chain 1 sender). */
        const val RICH_EXPORT = "android-template-rich-v4.json"

        /** Browser-authored format-4 template, produced by the web flow (chain 2 input). */
        const val WEB_TEMPLATE_INPUT = "web-template-native.json"

        /** Android re-export of the imported browser template (chain 2 return). */
        const val FROM_WEB_EXPORT = "android-template-from-web.json"

        /** Second template used only to prove that switching templates keeps a manual title. */
        const val SECOND_TEMPLATE_TITLE = "第二模板标题"
    }

    private val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val exchangeRoot: File = File(
        checkNotNull(targetContext.getExternalFilesDir(null)) { "需要外部文件目录" },
        "interop-stage5-template",
    )
    private val inDir = File(exchangeRoot, "in")
    private val outDir = File(exchangeRoot, "out")

    private lateinit var caseDir: File
    private lateinit var context: PrivateContext
    private lateinit var env: OrderTestEnvironment
    private lateinit var files: ContentResolverTemplateFileRepository

    /** Filesystem isolation: production services see only this directory tree. */
    private class PrivateContext(base: Context, val directory: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File =
            File(directory, "databases/$name").apply { parentFile!!.mkdirs() }

        override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()
    }

    @Before
    fun setUp() {
        exchangeRoot.mkdirs()
        inDir.mkdirs()
        outDir.mkdirs()
        caseDir = File(targetContext.cacheDir, "stage5-template-${System.nanoTime()}")
        caseDir.deleteRecursively()
        context = PrivateContext(targetContext, caseDir)
        env = OrderTestEnvironment(
            context,
            initialInstant = Instant.parse("2026-03-10T02:00:00Z"),
            databaseName = "stage5-template.db",
            inMemory = false,
        )
        files = ContentResolverTemplateFileRepository(context)
    }

    @After
    fun tearDown() {
        env.database.close()
        caseDir.deleteRecursively()
    }

    // ------------------------------------------------------------- chain 1 sender

    @Test
    fun a1ExportRichTemplateForBrowser() = runBlocking<Unit> {
        val draft = richTemplateDraft()
        assertEquals("the fixture draft must be valid", emptyMap<String, String>(), draft.validate())
        val templateId = env.templateRepository.saveTemplate(draft)
        // 区服 options live per game (UF-005); the production export reads them from there.
        env.serverOptionRepository.setOptions(draft.gameName, listOf("官服", "渠道服"))

        val library = TemplateLibraryViewModel(
            env.templateRepository,
            files,
            env.serverOptionRepository,
        )
        val target = File(outDir, RICH_EXPORT)
        library.exportTo(Uri.fromFile(target), templateId)
        awaitLibraryMessage(library)

        assertTrue("export wrote a file", target.isFile)
        val content = String(target.readBytes(), Charsets.UTF_8)
        assertTrue("declares format 4", content.contains("\"formatVersion\": 4"))
        // Read the file back through the production decoder before declaring it a fixture.
        val decoded = TemplateFileCodec.decode(target.readBytes())
        assertTrue("the exported file decodes", decoded is TemplateFileResult.Success)
        val roundTrip = (decoded as TemplateFileResult.Success).content.draft
        assertEquals(draft.gameName, roundTrip.gameName)
        assertEquals(draft.defaultOrderTitle, roundTrip.defaultOrderTitle)
        assertEquals(2, roundTrip.categories.size)

        writeProvenance(
            filename = "android-template-rich-v4-provenance.json",
            generator = "a1ExportRichTemplateForBrowser",
            description = "Android production template export (format 4), authored through the template repository",
            exportedFile = target,
            templateId = templateId,
            extra = buildJsonObject {
                put("templateFormat", TemplateFileCodec.FORMAT_VERSION)
                put("templateId", templateId)
                put("defaultOrderTitle", draft.defaultOrderTitle)
                put("categoryCount", roundTrip.categories.size)
                put("taskCount", roundTrip.taskCount)
                put("pricePresetCount", roundTrip.pricePresets.size)
                put("serverOptions", buildJsonArray { listOf("官服", "渠道服").forEach { add(JsonPrimitive(it)) } })
                put("hostingMode", draft.hostingMode.name)
                put("targetSessions", draft.targetSessionsText)
                put("writtenThrough", "TemplateLibraryViewModel.exportTo + ContentResolverTemplateFileRepository")
            },
        )
    }

    // ------------------------------------------------------------- chain 2 receiver

    @Test
    fun b1ImportWebTemplateCreateOrderAndExport() = runBlocking<Unit> {
        val source = File(inDir, WEB_TEMPLATE_INPUT)
        assertTrue(
            "the browser template fixture must be pushed to ${source.absolutePath}",
            source.isFile,
        )
        val sourceSha = sha256(source.readBytes())

        // ---- production import path: real stream → codec → preview → SaveAs resolution.
        val library = TemplateLibraryViewModel(
            env.templateRepository,
            files,
            env.serverOptionRepository,
        )
        library.onImportPicked(Uri.fromFile(source), WEB_TEMPLATE_INPUT)
        val preview = withTimeout(20_000) {
            library.uiState.first { it.importPreview != null || it.message != null }
        }.importPreview ?: throw AssertionError("import preview was not produced: ${library.uiState.value.message}")
        assertEquals("the preview counts the file's content", 2, preview.categoryCount)
        library.confirmImportSaveAs()
        val importedMessage = awaitLibraryMessage(library)
        assertTrue("import reported success: $importedMessage", importedMessage.contains("已另存为"))

        val summaries = env.templateRepository.findAllSummaries()
        val imported = summaries.first { it.gameName == preview.saveAsName }
        val template = checkNotNull(env.templateRepository.findTemplate(imported.id))

        // ---- persisted content (real Room rows, re-read through the repository)
        assertEquals("网页模板标题", template.defaultOrderTitle)
        // The browser template's two categories disagree on the hosting mode, so the file
        // declares no template-level opinion; Android must keep that null (inherit the
        // category consensus) instead of fabricating a DATE_RANGE default.
        assertNull("template-level mode must stay \"no opinion\"", template.hostingMode)
        assertEquals("web-1.0", template.templateVersion)
        assertEquals(2, template.categories.size)
        val countCategory = template.categories.first { it.name == "网页计次分类" }
        assertEquals(HostingMode.COUNT_BASED, countCategory.hostingMode)
        assertEquals(3, countCategory.targetSessions)
        val datedCategory = template.categories.first { it.name == "网页按日分类" }
        assertEquals(HostingMode.DATE_RANGE, datedCategory.hostingMode)
        assertEquals("1,3,5", datedCategory.serviceWeekdays)

        val cumulative = template.tasks.first { it.title == "抽数" }
        assertEquals(ProgressType.NUMERIC, cumulative.progressType)
        assertEquals(NumericMode.CUMULATIVE, cumulative.numericMode)
        assertEquals(0, java.math.BigDecimal("90").compareTo(cumulative.targetValue))
        assertEquals("抽", cumulative.unit)
        val everyOtherDay = template.tasks.first { it.title == "隔天副本" }
        assertEquals(RecurrenceType.EVERY_N_DAYS, everyOtherDay.recurrence)
        assertEquals(2, everyOtherDay.everyNDays)
        val weekdaysTask = template.tasks.first { it.title == "指定星期" }
        assertEquals(RecurrenceType.SELECTED_WEEKDAYS, weekdaysTask.recurrence)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), weekdaysTask.weekdays)
        assertEquals("2026-03-01", template.tasks.first { it.title == "限时活动" }.activeFrom?.toString())
        assertEquals(
            listOf(1250L, 6000L),
            template.pricePresets.sortedBy { it.sortOrder }.map { it.amountCents },
        )
        assertEquals(
            listOf("官服", "渠道服"),
            env.serverOptionRepository.observeForGame(template.gameName).first(),
        )

        // ---- create an order from the imported template through the production form logic
        val (owner, account) = env.seedOwnerAndAccount("模板交换老板", gameName = template.gameName)
        val secondTemplateId = env.templateRepository.saveTemplate(
            TemplateDraft(
                gameName = "第二模板游戏",
                templateVersion = "1.0",
                defaultOrderTitle = SECOND_TEMPLATE_TITLE,
                categories = listOf(
                    TemplateCategoryDraft(
                        name = "唯一分类",
                        tasks = listOf(TemplateTaskDraft(title = "日常", progressType = ProgressType.CHECKBOX)),
                    ),
                ),
            ),
        )

        val form = newOrderForm(owner.id, account.id)
        awaitFormLoaded(form)
        form.applyTemplate(template.id)
        val applied = withTimeout(15_000) { form.uiState.first { it.selectedTemplateId == template.id } }
        assertEquals("the imported default title fills an untouched form", "网页模板标题", applied.title)
        assertEquals(
            "every hosting task of the imported template is applied",
            listOf("签到", "抽数", "周常", "指定星期", "隔天副本", "限时活动").sorted(),
            applied.tasks.map { it.title }.sorted(),
        )
        assertEquals(HostingMode.DATE_RANGE, applied.hostingMode)

        // UF-036 provenance: a manual title is never overwritten by a later template switch.
        form.updateTitle("手填标题")
        form.applyTemplate(secondTemplateId)
        val afterSwitch = withTimeout(15_000) {
            form.uiState.first { it.selectedTemplateId == secondTemplateId }
        }
        assertEquals("switching templates must not rename a manual title", "手填标题", afterSwitch.title)
        // Clearing the title makes automatic application possible again.
        form.updateTitle("")
        form.applyTemplate(template.id)
        val reApplied = withTimeout(15_000) {
            form.uiState.first { it.selectedTemplateId == template.id && it.title.isNotBlank() }
        }
        assertEquals("an empty title accepts the template default again", "网页模板标题", reApplied.title)

        form.updatePrice("200.00")
        form.submit()
        val created = withTimeout(20_000) {
            form.uiState.first { it.isCreated || it.saveError != null }
        }
        assertNull("order creation failed: ${created.saveError}", created.saveError)
        val orderId = checkNotNull(created.createdOrderId)
        val order = checkNotNull(env.database.orderDao().findById(orderId))
        assertEquals("网页模板标题", order.title)
        assertEquals(HostingMode.DATE_RANGE, order.hostingMode)
        val periodId = env.database.orderDao().findPeriods(orderId).single().id
        val orderTasks = env.database.taskDao().findForPeriod(periodId)
        assertEquals(
            "the created order owns a copy of every applied task",
            listOf("签到", "抽数", "周常", "指定星期", "隔天副本", "限时活动").sorted(),
            orderTasks.map { it.title }.sorted(),
        )
        val orderCumulative = orderTasks.first { it.title == "抽数" }
        assertEquals(NumericMode.CUMULATIVE, orderCumulative.numericMode)
        assertEquals(0, java.math.BigDecimal("90").compareTo(orderCumulative.targetValue))

        // ---- production export of the imported template, read back by the browser
        val target = File(outDir, FROM_WEB_EXPORT)
        library.exportTo(Uri.fromFile(target), imported.id)
        awaitLibraryMessage(library)
        assertTrue("re-export wrote a file", target.isFile)
        val reDecoded = TemplateFileCodec.decode(target.readBytes())
        assertTrue("the re-export decodes", reDecoded is TemplateFileResult.Success)
        assertEquals(
            "the re-export still carries the imported default title",
            "网页模板标题",
            (reDecoded as TemplateFileResult.Success).content.draft.defaultOrderTitle,
        )

        writeProvenance(
            filename = "android-template-from-web-provenance.json",
            generator = "b1ImportWebTemplateCreateOrderAndExport",
            description = "Android production import of a browser template file, order creation from it, then production re-export",
            exportedFile = target,
            templateId = imported.id,
            extra = buildJsonObject {
                put("input", WEB_TEMPLATE_INPUT)
                put("inputSha256", sourceSha)
                put("importedGameName", template.gameName)
                put("createdOrderId", orderId)
                put("createdOrderTitle", order.title)
                put("manualTitleKeptOnSwitch", "手填标题")
                put("secondTemplateId", secondTemplateId)
                put("templateFormat", TemplateFileCodec.FORMAT_VERSION)
                put("writtenThrough", "TemplateLibraryViewModel.onImportPicked/confirmImportSaveAs/exportTo + NewOrderViewModel")
            },
        )
    }

    // ------------------------------------------------------------------- helpers

    private fun newOrderForm(ownerId: String, accountId: String): NewOrderViewModel =
        NewOrderViewModel(
            SavedStateHandle(
                mapOf(
                    Routes.OWNER_ID_ARG to ownerId,
                    Routes.ACCOUNT_ID_ARG to accountId,
                ),
            ),
            env.ownerRepository,
            env.accountRepository,
            env.orderRepository,
            env.clock,
            env.templateRepository,
        )

    private suspend fun awaitFormLoaded(form: NewOrderViewModel) {
        val state = withTimeout(20_000) { form.uiState.first { !it.loading } }
        assertNull("the form must load: ${state.loadError}", state.loadError)
    }

    private suspend fun awaitLibraryMessage(
        library: TemplateLibraryViewModel,
        timeoutMillis: Long = 20_000,
    ): String {
        val state = withTimeout(timeoutMillis) { library.uiState.first { !it.busy && it.message != null } }
        return checkNotNull(state.message)
    }

    /** Rich format-4 content: every portable field this contract version defines. */
    private fun richTemplateDraft(): TemplateDraft = TemplateDraft(
        gameName = "互通模板游戏",
        templateVersion = "2026.10-android",
        gameVersionNotes = "安卓侧合成模板：仅用于格式 4 互通验证",
        zoneText = "Asia/Shanghai",
        dailyRefresh = LocalTime.of(4, 0),
        weeklyRefreshDay = DayOfWeek.MONDAY,
        weeklyRefreshTime = LocalTime.of(5, 0),
        hostingMode = HostingMode.COUNT_BASED,
        hostingModeExplicit = true,
        targetSessionsText = "6",
        defaultOrderTitle = "安卓模板默认标题",
        categories = listOf(
            TemplateCategoryDraft(
                name = "计次分类",
                hostingMode = HostingMode.COUNT_BASED,
                targetSessionsText = "4",
                tasks = listOf(
                    TemplateTaskDraft(title = "签到", progressType = ProgressType.CHECKBOX),
                    TemplateTaskDraft(
                        title = "抽数累计",
                        progressType = ProgressType.NUMERIC,
                        startValue = "0",
                        targetValue = "120.5",
                        unit = "抽",
                        numericMode = NumericMode.CUMULATIVE,
                    ),
                ),
            ),
            TemplateCategoryDraft(
                name = "按日分类",
                hostingMode = HostingMode.DATE_RANGE,
                serviceWeekdays = "1,3,5",
                tasks = listOf(
                    TemplateTaskDraft(
                        title = "周常",
                        progressType = ProgressType.CHECKBOX,
                        recurrence = RecurrenceType.WEEKLY,
                    ),
                    TemplateTaskDraft(
                        title = "指定星期",
                        progressType = ProgressType.CHECKBOX,
                        recurrence = RecurrenceType.SELECTED_WEEKDAYS,
                        weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                    ),
                    TemplateTaskDraft(
                        title = "每月五日",
                        progressType = ProgressType.CHECKBOX,
                        recurrence = RecurrenceType.MONTHLY,
                        monthDay = "5,20",
                    ),
                    TemplateTaskDraft(
                        title = "隔三天",
                        progressType = ProgressType.CHECKBOX,
                        recurrence = RecurrenceType.EVERY_N_DAYS,
                        everyNDays = "3",
                    ),
                    TemplateTaskDraft(
                        title = "限时任务",
                        progressType = ProgressType.NUMERIC,
                        startValue = "0",
                        targetValue = "10",
                        unit = "次",
                        recurrence = RecurrenceType.DAILY,
                        numericMode = NumericMode.PER_OCCURRENCE,
                        activeFrom = "2026-03-01",
                        activeUntil = "2026-03-31",
                    ),
                ),
            ),
        ),
        pricePresets = listOf(
            TemplatePricePresetDraft(amountText = "30.00", label = "定金"),
            TemplatePricePresetDraft(amountText = "88.50", label = "全款"),
        ),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte)
        }

    private fun writeProvenance(
        filename: String,
        generator: String,
        description: String,
        exportedFile: File,
        templateId: String,
        extra: JsonObject,
    ) {
        val provenance = buildJsonObject {
            put("generatedBy", generator)
            put("description", description)
            put("appVersionName", com.daigan.record.BuildConfig.VERSION_NAME)
            put("appVersionCode", com.daigan.record.BuildConfig.VERSION_CODE)
            put("roomSchema", RECORD_DATABASE_VERSION)
            put("portableSchema", 15)
            put("templateFormat", TemplateFileCodec.FORMAT_VERSION)
            put("device", android.os.Build.MODEL)
            put("apiLevel", android.os.Build.VERSION.SDK_INT)
            put("hardware", if (isProbablyEmulator()) "emulator" else "physical")
            put("watermark", if (isProbablyEmulator()) "emulator-not-physical-device" else "physical-device")
            put("syntheticDataOnly", true)
            put("templateId", templateId)
            put(
                "files",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("file", exportedFile.name)
                            put("sha256", sha256(exportedFile.readBytes()))
                            put("sizeBytes", exportedFile.length())
                        },
                    )
                },
            )
            put("extra", extra)
        }
        File(outDir, filename).writeText(
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), provenance),
            Charsets.UTF_8,
        )
    }

    private fun isProbablyEmulator(): Boolean =
        android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.MODEL.contains("sdk_gphone") ||
            android.os.Build.PRODUCT.contains("sdk")
}
