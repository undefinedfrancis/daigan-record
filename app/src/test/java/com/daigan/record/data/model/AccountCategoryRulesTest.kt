package com.daigan.record.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** UF-037 账号分类名称规则：trim 后精确查重，不发明大小写/语言合并规则。 */
class AccountCategoryRulesTest {

    @Test
    fun `空白名称被规范化为 null`() {
        assertNull(AccountCategoryRules.normalizeName(null))
        assertNull(AccountCategoryRules.normalizeName(""))
        assertNull(AccountCategoryRules.normalizeName("   "))
    }

    @Test
    fun `名称首尾空白被去掉`() {
        assertEquals("常客", AccountCategoryRules.normalizeName("  常客  "))
    }

    @Test
    fun `空名被拒绝`() {
        assertEquals("请填写分类名称", AccountCategoryRules.validateName("  "))
        assertEquals("请填写分类名称", AccountCategoryRules.validateName(null))
    }

    @Test
    fun `trim 后同名被拒绝`() {
        assertEquals("已存在同名分类", AccountCategoryRules.validateName(" 常客 ", listOf("常客")))
    }

    @Test
    fun `重命名时排除自身不会误判重名`() {
        // 仓库层传入的是「其他分类」的名称，因此自身名称不在集合里。
        assertNull(AccountCategoryRules.validateName("常客", listOf("朋友")))
    }

    @Test
    fun `大小写不同视为不同名称`() {
        assertNull(AccountCategoryRules.validateName("VIP", listOf("vip")))
    }

    @Test
    fun `名称按码点计数上限 30`() {
        assertNull(AccountCategoryRules.validateName("客".repeat(30)))
        assertNotNull(AccountCategoryRules.validateName("客".repeat(31)))
        // 30 个 emoji 是 30 个码点，必须放行（UTF-16 长度是 60）。
        assertNull(AccountCategoryRules.validateName("😀".repeat(30)))
    }
}
