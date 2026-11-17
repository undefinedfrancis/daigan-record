package com.daigan.record.data.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Creation/template contract of 打卡式托管 (UF-033/UF-034): a positive target is
 * required, no date or weekday is required, and the fingerprint change is limited to the
 * new mode so fingerprints persisted for date-based orders stay valid.
 */
class CountHostingDraftTest {

    private val today = LocalDate.of(2026, 10, 21)

    private fun draft(
        mode: HostingMode = HostingMode.COUNT_BASED,
        target: Int? = 30,
        start: LocalDate = today,
        end: LocalDate = today.plusDays(29),
    ) = HostingOrderDraft(
        ownerId = "owner-1",
        accountId = "account-1",
        title = "打卡式托管",
        startDate = start,
        endDate = end,
        priceYuan = "120",
        tasks = listOf(TaskDraft(title = "日常签到")),
        hostingMode = mode,
        targetSessions = target,
    )

    @Test
    fun countModeRequiresAPositiveTarget() {
        assertTrue(draft(target = 30).validate().isEmpty())
        assertEquals("请填写大于 0 的打卡次数", draft(target = null).validate()["targetSessions"])
        assertEquals("请填写大于 0 的打卡次数", draft(target = 0).validate()["targetSessions"])
        assertEquals(
            "打卡次数最多 ${CountHosting.MAX_TARGET_SESSIONS} 次",
            draft(target = CountHosting.MAX_TARGET_SESSIONS + 1).validate()["targetSessions"],
        )
    }

    @Test
    fun countModeIgnoresDateAndWeekdayRequirements() {
        // An inverted "date range" is meaningless in this mode and must not be reported:
        // the user was never asked for one and nothing is persisted as a business range.
        val errors = draft(start = today.plusDays(10), end = today).validate()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun dateModeStillValidatesItsDateRange() {
        val errors = draft(
            mode = HostingMode.DATE_RANGE,
            target = null,
            start = today.plusDays(10),
            end = today,
        ).validate()
        assertEquals("到期日期不能早于开始日期", errors["dates"])
    }

    @Test
    fun countFingerprintCoversModeAndTarget() {
        val base = draft(target = 30)
        assertNotEquals(
            OrderCommandFingerprint.hosting(base),
            OrderCommandFingerprint.hosting(base.copy(targetSessions = 31)),
        )
        assertNotEquals(
            OrderCommandFingerprint.hosting(base),
            OrderCommandFingerprint.hosting(
                draft(mode = HostingMode.DATE_RANGE, target = null),
            ),
        )
    }

    @Test
    fun dateFingerprintIsUnchangedByAnUnusedTarget() {
        // Legacy date-based commands must keep byte-identical fingerprints: a stale
        // target value in the form cannot change the retry identity.
        val withoutTarget = draft(mode = HostingMode.DATE_RANGE, target = null)
        val withIgnoredTarget = draft(mode = HostingMode.DATE_RANGE, target = 30)
        assertEquals(
            OrderCommandFingerprint.hosting(withoutTarget),
            OrderCommandFingerprint.hosting(withIgnoredTarget),
        )
    }

    @Test
    fun templateDraftCarriesCountModeAndTarget() {
        val template = TemplateDraft(
            gameName = "测试游戏",
            categories = listOf(
                TemplateCategoryDraft(
                    name = "日常",
                    tasks = listOf(TemplateTaskDraft(title = "签到")),
                ),
            ),
            hostingMode = HostingMode.COUNT_BASED,
            targetSessionsText = "30",
        )
        assertTrue(template.validate().isEmpty())
        assertEquals(30, template.targetSessionsOrNull())

        val missingTarget = template.copy(targetSessionsText = "")
        assertEquals(
            "请填写打卡次数",
            missingTarget.validate()["targetSessions"],
        )
        val invalidTarget = template.copy(targetSessionsText = "0")
        assertEquals(
            "打卡次数需大于 0",
            invalidTarget.validate()["targetSessions"],
        )
    }

    @Test
    fun dateTemplateDoesNotRequireATarget() {
        val template = TemplateDraft(
            gameName = "测试游戏",
            categories = listOf(
                TemplateCategoryDraft(
                    name = "日常",
                    tasks = listOf(TemplateTaskDraft(title = "签到")),
                ),
            ),
        )
        assertTrue(template.validate().isEmpty())
        assertNull(template.targetSessionsOrNull())
    }
}
