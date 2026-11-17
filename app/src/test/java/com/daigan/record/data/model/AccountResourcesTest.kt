package com.daigan.record.data.model

import org.junit.Assert.*
import org.junit.Test

class AccountResourcesTest {
    @Test fun `legacy text survives adding and removing structured resources`() {
        val legacy = "抽数：30\n原有备注"
        val loaded = AccountResources.decode(legacy)
        val entry = AccountResource("stable", "原石", "1200")
        val encoded = AccountResources.encode(loaded.copy(entries = listOf(entry)))
        assertEquals(legacy, AccountResources.decode(encoded).notes)
        assertEquals(listOf(entry), AccountResources.decode(encoded).entries)
        assertEquals(legacy, AccountResources.encode(loaded))
    }
    @Test fun `malformed or unrelated JSON is preserved as original text`() {
        listOf("{bad", "{\"entries\":[]}").forEach {
            assertEquals(it, AccountResources.decode(it).notes)
        }
    }
    @Test fun `structured round trip preserves stable identities and arbitrary values`() {
        val resources = AccountResources("旧备注", listOf(AccountResource("a", "体力", "12/180"), AccountResource("b", "票", "")))
        assertEquals(resources, AccountResources.decode(AccountResources.encode(resources)))
        assertTrue(resources.displayText().contains("体力：12/180"))
    }
}
