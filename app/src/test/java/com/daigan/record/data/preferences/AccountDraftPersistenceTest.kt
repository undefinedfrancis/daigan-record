package com.daigan.record.data.preferences

import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.AccountResource
import com.daigan.record.data.model.AccountResources
import com.daigan.record.testing.InMemoryUiPreferenceStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccountDraftPersistenceTest {
    @Test fun `reopening preference store preserves incomplete draft and resources without mixing identities`() = runTest {
        val file = mutableMapOf<String, String>()
        val first = InMemoryUiPreferenceStore(customBacking = file)
        val draft = AccountDraft(gameName = "未完成\u001f表单", resourceNotes = AccountResources.encode(
            AccountResources("原备注", listOf(AccountResource("stable", "票", "12"))),
        ))
        first.setAccountDraft("dataset-1/owner/new", draft)
        first.setAccountDraft("dataset-1/owner/edit-a", AccountDraft(server = "另一个草稿"))
        val restarted = InMemoryUiPreferenceStore(customBacking = file)
        assertEquals(draft, restarted.accountDraft("dataset-1/owner/new"))
        assertNull(restarted.accountDraft("dataset-2/owner/new"))
        restarted.clearAccountDraft("dataset-1/owner/edit-a")
        assertEquals(draft, restarted.accountDraft("dataset-1/owner/new"))
    }
    @Test fun `corrupt draft does not crash or restore partial values`() {
        assertNull(AccountDraftCodec.decode("bad draft"))
    }
}
