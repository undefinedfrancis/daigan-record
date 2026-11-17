package com.daigan.record.feature.accounts

import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.testing.account
import com.daigan.record.testing.owner
import org.junit.Assert.*
import org.junit.Test

class AccountSearchTest {
    private val summary = OwnerSummary(owner(nickname = "阿明", number = 12), listOf(account().copy(
        gameName = "鸣潮", server = "港澳服", uid = "UID-5599", loginAccount = "mail@example.com", password = "hidden-secret",
    )))
    @Test fun `search covers safe account fields and owner identifiers`() {
        listOf("阿明", "B012", "UID-5599", "鸣潮", "港澳", "MAIL@example").forEach {
            assertTrue("Expected match for $it", matchesAccountSearch(summary, it))
        }
    }
    @Test fun `search never uses password and trims whitespace`() {
        assertFalse(matchesAccountSearch(summary, "hidden-secret"))
        assertTrue(matchesAccountSearch(summary, "  UID-5599  "))
        assertTrue(matchesAccountSearch(summary, ""))
    }
}
