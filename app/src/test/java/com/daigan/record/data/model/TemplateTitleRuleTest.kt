package com.daigan.record.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UF-036：模板默认订单标题纯规则与统一 60 码点上限。 */
class TemplateTitleRuleTest {

    // ---------------------------------------------------------------- 套用规则

    @Test
    fun `空白标题接受模板默认值`() {
        assertEquals("日常托管", TemplateTitleRule.resolveOrderTitle("", null, "日常托管"))
        assertEquals("日常托管", TemplateTitleRule.resolveOrderTitle("   ", null, "日常托管"))
    }

    @Test
    fun `上一次自动带入的值可被新模板默认值替换`() {
        val resolved = TemplateTitleRule.resolveOrderTitle("日常托管", "日常托管", "周末托管")
        assertEquals("周末托管", resolved)
    }

    @Test
    fun `手动编辑的标题永不被模板切换覆盖`() {
        val resolved = TemplateTitleRule.resolveOrderTitle("老板指定标题", "日常托管", "周末托管")
        assertEquals("老板指定标题", resolved)
    }

    @Test
    fun `空白模板默认值不清空已有标题`() {
        assertEquals("已有标题", TemplateTitleRule.resolveOrderTitle("已有标题", null, null))
        assertEquals("已有标题", TemplateTitleRule.resolveOrderTitle("已有标题", null, "   "))
    }

    @Test
    fun `用户清空标题后可再次自动带入`() {
        val cleared = TemplateTitleRule.resolveOrderTitle("", "日常托管", "日常托管")
        assertEquals("日常托管", cleared)
    }

    @Test
    fun `相同默认值重复套用不改变标题`() {
        val resolved = TemplateTitleRule.resolveOrderTitle("日常托管", "日常托管", "日常托管")
        assertEquals("日常托管", resolved)
        assertFalse(TemplateTitleRule.wouldApply("日常托管", "日常托管", "日常托管"))
    }

    // ---------------------------------------------------------------- 出处记录

    @Test
    fun `套用成功后记录自动带入值`() {
        assertEquals(
            "日常托管",
            TemplateTitleRule.nextProvenance("", null, "日常托管"),
        )
    }

    @Test
    fun `未套用时保留旧记录`() {
        assertEquals(
            "旧默认",
            TemplateTitleRule.nextProvenance("老板指定标题", "旧默认", "新默认"),
        )
    }

    @Test
    fun `空白默认值不更新记录`() {
        assertNull(TemplateTitleRule.nextProvenance("", null, null))
        assertEquals(
            "旧默认",
            TemplateTitleRule.nextProvenance("旧默认", "旧默认", "   "),
        )
    }

    @Test
    fun `wouldApply 只在标题实际变化时为真`() {
        assertTrue(TemplateTitleRule.wouldApply("", null, "日常托管"))
        assertFalse(TemplateTitleRule.wouldApply("手动", null, "日常托管"))
        assertFalse(TemplateTitleRule.wouldApply("标题", null, null))
    }

    // ---------------------------------------------------------------- 归一与校验

    @Test
    fun `normalizeDefaultOrderTitle 去首尾空白并把空白映射为 null`() {
        assertEquals("日常托管", TemplateTitleRule.normalizeDefaultOrderTitle("  日常托管  "))
        assertNull(TemplateTitleRule.normalizeDefaultOrderTitle("   "))
        assertNull(TemplateTitleRule.normalizeDefaultOrderTitle(null))
    }

    @Test
    fun `默认订单标题按码点计数上限 60`() {
        assertNull(TemplateTitleRule.validateDefaultOrderTitle("a".repeat(60)))
        assertNull(TemplateTitleRule.validateDefaultOrderTitle("😀".repeat(30)))
        assertTrue(
            TemplateTitleRule.validateDefaultOrderTitle("a".repeat(61)) != null,
        )
        assertTrue(
            TemplateTitleRule.validateDefaultOrderTitle("😀".repeat(61)) != null,
        )
    }

    @Test
    fun `模板默认值与订单标题上限一致`() {
        assertEquals(
            ShortOrderDraft.MAX_TITLE_LENGTH,
            TemplateTitleRule.MAX_DEFAULT_ORDER_TITLE_CODE_POINTS,
        )
    }

    // ---------------------------------------------------------------- 订单标题上限

    @Test
    fun `订单标题上限按码点而非 UTF-16 长度判定`() {
        // 31 个 emoji：UTF-16 length = 62，但只有 31 个码点，必须放行。
        val emojiTitle = "😀".repeat(31)
        assertEquals(62, emojiTitle.length)
        assertFalse(ShortOrderDraft.titleExceedsLimit(emojiTitle))
        assertTrue(ShortOrderDraft.titleExceedsLimit("a".repeat(61)))
        assertFalse(ShortOrderDraft.titleExceedsLimit("a".repeat(60)))
    }

    @Test
    fun `短期订单校验对超长标题报错且 emoji 标题不误判`() {
        val overLong = shortDraft(title = "a".repeat(61)).validate()
        assertEquals("订单名称最多 60 个字符", overLong["title"])

        val emoji = shortDraft(title = "😀".repeat(31)).validate()
        assertNull(emoji["title"])
    }

    private fun shortDraft(title: String): ShortOrderDraft = ShortOrderDraft(
        ownerId = "owner-1",
        accountId = "account-1",
        title = title,
        priceYuan = "10.00",
    )
}
