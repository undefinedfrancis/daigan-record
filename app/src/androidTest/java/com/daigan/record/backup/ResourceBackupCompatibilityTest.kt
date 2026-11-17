package com.daigan.record.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.data.model.AccountDraft
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.testing.OrderTestEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UF-017: the account's existing resource text must survive export and restore, and a
 * backup written before the column existed must still restore (as NULL) instead of being
 * rejected or silently dropping the rest of the account.
 *
 * This exercises the real catalog-driven read/write pair (`BackupDatabaseAccess.dump`
 * and `replaceAll`) rather than a hand-built dataset, so a missing catalog entry would
 * fail here even if the codec unit tests passed.
 */
@RunWith(AndroidJUnit4::class)
class ResourceBackupCompatibilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var env: OrderTestEnvironment
    private lateinit var access: BackupDatabaseAccess

    @Before
    fun setUp() {
        env = OrderTestEnvironment(context)
        access = BackupDatabaseAccess(context, env.database, env.clock)
    }

    @After
    fun tearDown() {
        env.close()
    }

    private suspend fun seedAccountWithResources(
        resources: String = "3 星专武 · 60 抽",
        device: String = "平板",
    ): String {
        val (owner, _) = env.seedOwnerAndAccount()
        val account = env.accountRepository.createAccount(
            owner.id,
            AccountDraft(
                gameName = "重返未来：1999",
                server = "官服",
                uid = "10000628",
                loginAccount = "login-resource",
                loginMethod = LoginMethod.PASSWORD,
                password = "secret-value",
                loginDevice = device,
                resourceNotes = resources,
            ),
        )
        return account.id
    }

    @Test
    fun currentBackupCarriesResourceNotesThroughEncodeDecodeAndRestore() = runBlocking {
        val accountId = seedAccountWithResources()
        val dump = access.dump()
        val table = dump.tables.single { it.table.name == "game_accounts" }
        assertTrue(
            "the export catalog must contain the column, otherwise it is silently dropped",
            "resourceNotes" in table.table.columnNames,
        )
        val row = table.rows.single { it[table.table.columnNames.indexOf("id")] == JsonPrimitive(accountId) }
        assertEquals(
            "3 星专武 · 60 抽",
            (row[table.table.columnNames.indexOf("resourceNotes")] as JsonPrimitive).content,
        )

        // Encoding, decoding and restoring keeps it identical.
        val decoded = BackupDataCodec.decode(BackupDataCodec.encode(dump))
        val restoredRow = decoded.tables.single { it.table.name == "game_accounts" }.let { tableRows ->
            tableRows.rows.single { it[tableRows.table.columnNames.indexOf("id")] == JsonPrimitive(accountId) }
        }
        assertEquals(
            "3 星专武 · 60 抽",
            (restoredRow[decoded.tables.single { it.table.name == "game_accounts" }
                .table.columnNames.indexOf("resourceNotes")] as JsonPrimitive).content,
        )

        access.replaceAll(decoded, restoreId = "restore-current")
        val restored = env.accountRepository.findAccount(accountId)!!
        assertEquals("3 星专武 · 60 抽", restored.resourceNotes)
        assertEquals("平板", restored.loginDevice)
        assertEquals("secret-value", restored.password)
        assertEquals("login-resource", restored.loginAccount)
    }

    /** 把当前编码结果重标为历史便携版本（只改声明版本，用来表达"当年就没有这一列"）。 */
    private fun legacyPayload(dataSet: BackupDataSet, schemaVersion: Int): String {
        val root = Json.parseToJsonElement(BackupDataCodec.encode(dataSet)).jsonObject
        val patched = JsonObject(root + mapOf("schemaVersion" to JsonPrimitive(schemaVersion)))
        return Json.encodeToString(JsonElement.serializer(), patched)
    }

    @Test
    fun legacyBackupWithoutResourceNotesRestoresNullAndKeepsEveryOtherField() = runBlocking {
        val accountId = seedAccountWithResources(resources = "会被旧备份丢弃的资源")
        val dump = access.dump()
        val accountTable = dump.tables.single { it.table.name == "game_accounts" }
        val columnIndex = accountTable.table.columnNames.indexOf("resourceNotes")

        // A backup written before schema 11: the column is absent from the payload.
        val legacy = BackupDataSet(
            dump.tables.map { tableRows ->
                if (tableRows.table.name != "game_accounts") {
                    tableRows
                } else {
                    BackupDataSet.TableRows(
                        table = tableRows.table.copy(
                            columns = tableRows.table.columns.filterIndexed { index, _ ->
                                index != columnIndex
                            },
                        ),
                        rows = tableRows.rows.map { row ->
                            row.filterIndexed { index, _ -> index != columnIndex }
                        },
                    )
                }
            },
        )

        // 历史文件必须在**外层与数据内层**都声明更老的便携版本，否则 schema 15 的
        // 严格列规则（缺列即拒绝）会先于"历史可缺列"规则生效。
        val decoded = BackupDataCodec.decode(legacyPayload(legacy, schemaVersion = 10), 10)
        val decodedAccountTable = decoded.tables.single { it.table.name == "game_accounts" }
        assertTrue(
            "a legacy payload decodes against the current catalog",
            "resourceNotes" in decodedAccountTable.table.columnNames,
        )
        access.replaceAll(decoded, restoreId = "restore-legacy")

        val restored = env.accountRepository.findAccount(accountId)!!
        assertNull("a legacy backup restores NULL, not an empty overwrite", restored.resourceNotes)
        assertEquals("重返未来：1999", restored.gameName)
        assertEquals("10000628", restored.uid)
        assertEquals("login-resource", restored.loginAccount)
        assertEquals("secret-value", restored.password)
        assertEquals("平板", restored.loginDevice)
        assertEquals(LoginMethod.PASSWORD, restored.loginMethod)
    }

    @Test
    fun exportStillWorksWhenResourceNotesIsNull() = runBlocking {
        val (owner, account) = env.seedOwnerAndAccount()
        assertNull("fixture precondition", env.accountRepository.findAccount(account.id)!!.resourceNotes)
        val dump = access.dump()
        val table = dump.tables.single { it.table.name == "game_accounts" }
        val row = table.rows.single { it[table.table.columnNames.indexOf("id")] == JsonPrimitive(account.id) }
        assertNull(row[table.table.columnNames.indexOf("resourceNotes")])
        access.replaceAll(BackupDataCodec.decode(BackupDataCodec.encode(dump)), restoreId = "restore-null")
        val restored = env.accountRepository.findAccount(account.id)!!
        assertNull(restored.resourceNotes)
        assertEquals(owner.id, restored.ownerId)
    }
}
