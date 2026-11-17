package com.daigan.record.data.template

import com.daigan.record.data.model.HostingMode
import com.daigan.record.testing.TemplateFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UF-034: a template's hosting mode and agreed round count must survive export/import.
 * The fields are optional, so files written by older builds still import unchanged.
 */
class TemplateCountModeCodecTest {

    private val template = TemplateFixtures.template().copy(hostingMode = HostingMode.DATE_RANGE)

    @Test
    fun countModeAndTargetSurviveExportAndImport() {
        val countBased = template.copy(
            hostingMode = HostingMode.COUNT_BASED,
            targetSessions = 30,
        )
        val bytes = TemplateFileCodec.encode(countBased)
        val result = TemplateFileCodec.decode(bytes)
        assertTrue(result is TemplateFileResult.Success)
        val draft = (result as TemplateFileResult.Success).content.draft
        assertEquals(HostingMode.COUNT_BASED, draft.hostingMode)
        assertEquals("30", draft.targetSessionsText)
    }

    @Test
    fun dateBasedTemplateWritesExplicitDateRangeAndImportsAsDateBased() {
        // 模板格式 4 显式写出分层默认值：按日期就是 DATE_RANGE，绝不留下需要猜的 null，
        // 也绝不携带目标次数。
        val bytes = TemplateFileCodec.encode(template)
        val json = String(bytes, Charsets.UTF_8)
        assertTrue(json.contains("\"formatVersion\": 4"))
        assertTrue(json.contains("\"hostingMode\": \"DATE_RANGE\""))
        assertTrue(json.contains("\"targetSessions\": null"))
        val draft = (TemplateFileCodec.decode(bytes) as TemplateFileResult.Success).content.draft
        assertEquals(HostingMode.DATE_RANGE, draft.hostingMode)
        assertEquals("", draft.targetSessionsText)
    }

    @Test
    fun aCountBasedFileWithoutATargetIsRejected() {
        val countBased = template.copy(hostingMode = HostingMode.COUNT_BASED, targetSessions = null)
        val json = String(TemplateFileCodec.encode(countBased), Charsets.UTF_8)
        val result = TemplateFileCodec.decode(json.toByteArray(Charsets.UTF_8))
        assertTrue(result is TemplateFileResult.Failure)
    }
}
