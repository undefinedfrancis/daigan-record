package com.daigan.record.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.preferences.SharedPreferencesUiPreferenceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run seed and verify in separate instrumentation processes with an explicit argument. */
@RunWith(AndroidJUnit4::class)
class AccountDraftProcessProbeTest {
    private val key = "candidate-process-probe:dataset:owner:new"
    private val draft = AccountDraft(gameName="草稿进程探针",server="官服",uid="draft-process-uid",
        loginAccount="draft-login",loginMethod=LoginMethod.VERIFICATION_CODE,resourceNotes="资源草稿保留")
    private fun store() = SharedPreferencesUiPreferenceStore(ApplicationProvider.getApplicationContext<Context>())
    @Test fun seedDraft() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("draftProcessProbe") == "true")
        store().setAccountDraft(key,draft)
        assertEquals(draft,store().accountDraft(key))
    }
    @Test fun verifyDraftInNewProcess() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("draftProcessProbe") == "true")
        val store=store()
        assertEquals(draft,store.accountDraft(key))
        assertNull(store.accountDraft("candidate-process-probe:other-dataset:owner:new"))
        store.clearAccountDraft(key)
        assertNull(store.accountDraft(key))
    }
}
