package com.daigan.record.data.template

import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.GameTemplate
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TemplateCategory
import com.daigan.record.data.model.TemplateOrigin
import com.daigan.record.data.model.TemplateTask
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板交换格式 4：写出完整分层默认值、严格拒绝未知键、历史 1–3 按当年键集可读。
 *
 * 账号分类、账号编号、订单编号与进度历史**绝不会**出现在模板文件里。
 */
class TemplateFileCodecV4Test {

    private val now = Instant.ofEpochMilli(1_760_000_000_000)

    private fun template(
        hostingMode: HostingMode = HostingMode.DATE_RANGE,
        targetSessions: Int? = null,
        serviceWeekdays: String? = null,
        defaultOrderTitle: String? = null,
        categoryMode: HostingMode? = HostingMode.DATE_RANGE,
        categoryTarget: Int? = null,
        categoryWeekdays: String? = null,
    ): GameTemplate {
        val categoryId = "cat-1"
        return GameTemplate(
            id = "tpl-1",
            gameName = "测试游戏",
            templateVersion = "1.0",
            gameVersionNotes = null,
            refresh = GameRefreshRulesDomain(
                zoneId = ZoneId.of("Asia/Shanghai"),
                dailyRefresh = LocalTime.of(5, 0),
                weeklyRefreshDay = DayOfWeek.MONDAY,
                weeklyRefreshTime = LocalTime.of(5, 0),
            ),
            origin = TemplateOrigin.USER,
            builtInKey = null,
            createdAt = now,
            updatedAt = now,
            categories = listOf(
                TemplateCategory(
                    id = categoryId,
                    templateId = "tpl-1",
                    name = "日常",
                    sortOrder = 0,
                    hostingMode = categoryMode,
                    targetSessions = categoryTarget,
                    serviceWeekdays = categoryWeekdays,
                    tasks = listOf(
                        TemplateTask(
                            id = "task-1",
                            templateId = "tpl-1",
                            categoryId = categoryId,
                            title = "签到",
                            progressType = ProgressType.NUMERIC,
                            startValue = BigDecimal("0"),
                            targetValue = BigDecimal("10"),
                            unit = "次",
                            recurrence = RecurrenceType.DAILY,
                            weekdays = emptySet(),
                            everyNDays = null,
                            sortOrder = 0,
                            activeFrom = LocalDate.parse("2026-02-01"),
                            activeUntil = LocalDate.parse("2026-02-28"),
                            numericMode = NumericMode.CUMULATIVE,
                        ),
                    ),
                ),
            ),
            hostingMode = hostingMode,
            targetSessions = targetSessions,
            serviceWeekdays = serviceWeekdays,
            defaultOrderTitle = defaultOrderTitle,
        )
    }

    private fun decode(text: String) = TemplateFileCodec.decode(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `format 4 writes every layered default and round-trips`() {
        val text = String(
            TemplateFileCodec.encode(
                template(
                    hostingMode = HostingMode.COUNT_BASED,
                    targetSessions = 12,
                    defaultOrderTitle = "托管 12 次",
                    categoryMode = HostingMode.COUNT_BASED,
                    categoryTarget = 6,
                ),
            ),
            Charsets.UTF_8,
        )
        assertTrue(text.contains("\"formatVersion\": 4"))
        assertTrue(text.contains("\"defaultOrderTitle\": \"托管 12 次\""))

        val draft = (decode(text) as TemplateFileResult.Success).content.draft
        assertEquals(HostingMode.COUNT_BASED, draft.hostingMode)
        assertEquals("12", draft.targetSessionsText)
        assertEquals("托管 12 次", draft.defaultOrderTitle)
        assertEquals(HostingMode.COUNT_BASED, draft.categories.single().hostingMode)
        assertEquals("6", draft.categories.single().targetSessionsText)
        assertEquals("2026-02-01", draft.categories.single().tasks.single().activeFrom)
        assertEquals("2026-02-28", draft.categories.single().tasks.single().activeUntil)
        assertEquals(NumericMode.CUMULATIVE, draft.categories.single().tasks.single().numericMode)
    }

    @Test
    fun `category inherit is written as null and read back as inherit`() {
        val text = String(
            TemplateFileCodec.encode(template(categoryMode = null)),
            Charsets.UTF_8,
        )
        assertTrue(text.contains("\"hostingMode\": null"))
        val draft = (decode(text) as TemplateFileResult.Success).content.draft
        assertNull(draft.categories.single().hostingMode)
    }

    @Test
    fun `weekday defaults survive round-trip`() {
        val text = String(
            TemplateFileCodec.encode(
                template(
                    serviceWeekdays = "1,2,3,4,5",
                    categoryWeekdays = "1,2,3,4,5",
                ),
            ),
            Charsets.UTF_8,
        )
        val draft = (decode(text) as TemplateFileResult.Success).content.draft
        assertEquals("1,2,3,4,5", draft.serviceWeekdays)
        assertEquals("1,2,3,4,5", draft.categories.single().serviceWeekdays)
    }

    @Test
    fun `unknown keys are rejected at every level`() {
        val base = String(TemplateFileCodec.encode(template()), Charsets.UTF_8)
        val root = base.replaceFirst("\"template\"", "\"ghost\": 1,\n  \"template\"")
        assertTrue(decode(root) is TemplateFileResult.Failure)

        val category = base.replaceFirst("\"name\": \"日常\"", "\"name\": \"日常\", \"ghost\": 2")
        assertTrue(decode(category) is TemplateFileResult.Failure)

        val task = base.replaceFirst("\"title\": \"签到\"", "\"title\": \"签到\", \"ghost\": 3")
        assertTrue(decode(task) is TemplateFileResult.Failure)
    }

    @Test
    fun `a template file never carries account categories or business ids`() {
        val text = String(TemplateFileCodec.encode(template()), Charsets.UTF_8)
        listOf("account_categories", "ownerId", "accountId", "orderId", "task_progress_events")
            .forEach { assertTrue("模板文件不应包含 $it", !text.contains(it)) }
    }

    @Test
    fun `historical format 1 and 3 remain readable through their own key sets`() {
        val v1 = """
            {
              "format": "daigan-template",
              "formatVersion": 1,
              "template": {
                "id": "tpl-1",
                "gameName": "测试游戏",
                "templateVersion": "1.0",
                "gameVersionNotes": null,
                "timeZone": "Asia/Shanghai",
                "dailyRefresh": "05:00",
                "weeklyRefreshDay": "MONDAY",
                "weeklyRefreshTime": "05:00",
                "categories": [
                  {
                    "id": "cat-1",
                    "name": "日常",
                    "tasks": [
                      {
                        "id": "task-1",
                        "categoryId": "cat-1",
                        "title": "签到",
                        "progressType": "CHECKBOX",
                        "startValue": "0",
                        "targetValue": "1",
                        "unit": null,
                        "recurrence": "DAILY",
                        "weekdays": [],
                        "everyNDays": null
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val v1Draft = (decode(v1) as TemplateFileResult.Success).content.draft
        assertEquals(HostingMode.DATE_RANGE, v1Draft.hostingMode)
        assertNull(v1Draft.categories.single().hostingMode)

        val v3 = v1
            .replace("\"formatVersion\": 1", "\"formatVersion\": 3")
            .replace(
                "\"categories\": [",
                "\"hostingMode\": \"COUNT_BASED\", \"targetSessions\": 9, \"categories\": [",
            )
        val v3Draft = (decode(v3) as TemplateFileResult.Success).content.draft
        assertEquals(HostingMode.COUNT_BASED, v3Draft.hostingMode)
        assertEquals("9", v3Draft.targetSessionsText)
    }

    @Test
    fun `historical web format 2 normalises serviceMode without losing the date choice`() {
        val webV2 = """
            {
              "format": "daigan-template",
              "formatVersion": 2,
              "template": {
                "id": "tpl-1",
                "gameName": "测试游戏",
                "templateVersion": "1.0",
                "gameVersionNotes": null,
                "timeZone": "Asia/Shanghai",
                "dailyRefresh": "05:00",
                "weeklyRefreshDay": "MONDAY",
                "weeklyRefreshTime": "05:00",
                "serviceMode": "count",
                "serviceCount": 7,
                "categories": [
                  {
                    "id": "cat-1",
                    "name": "显式日期",
                    "serviceMode": null,
                    "tasks": [
                      {
                        "id": "task-1",
                        "categoryId": "cat-1",
                        "title": "签到",
                        "progressType": "CHECKBOX",
                        "startValue": "0",
                        "targetValue": "1",
                        "unit": null,
                        "recurrence": "DAILY",
                        "weekdays": [],
                        "everyNDays": null,
                        "monthDay": null,
                        "activeFrom": null,
                        "activeUntil": null
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val draft = (decode(webV2) as TemplateFileResult.Success).content.draft
        assertEquals(HostingMode.COUNT_BASED, draft.hostingMode)
        assertEquals("7", draft.targetSessionsText)
        // 分类存 null = 显式按日期（不是"继承"）。
        assertEquals(HostingMode.DATE_RANGE, draft.categories.single().hostingMode)
    }

    @Test
    fun `shared decimal boundary is exact at one trillion`() {
        val boundary = template(
            categoryMode = HostingMode.DATE_RANGE,
        ).let { base ->
            val category = base.categories.single()
            val task = category.tasks.single()
            base.copy(
                categories = listOf(
                    category.copy(
                        tasks = listOf(
                            task.copy(
                                startValue = BigDecimal("999999999999.999"),
                                targetValue = BigDecimal("1000000000000"),
                            ),
                        ),
                    ),
                ),
            )
        }
        val text = String(TemplateFileCodec.encode(boundary), Charsets.UTF_8)
        val draft = decode(text)
        assertTrue(draft is TemplateFileResult.Success)
        assertEquals(
            "1000000000000",
            (draft as TemplateFileResult.Success).content.draft.categories.single().tasks.single().targetValue,
        )

        // 四位小数越界：读取端必须拒绝，而不是悄悄四舍五入。
        val tooPrecise = text.replace("\"targetValue\": \"1000000000000\"", "\"targetValue\": \"1000000000000.0001\"")
        assertTrue(decode(tooPrecise) is TemplateFileResult.Failure)
    }
}
