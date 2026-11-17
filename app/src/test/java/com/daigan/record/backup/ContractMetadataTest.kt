package com.daigan.record.backup

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 产品级契约元数据的生成器测试。
 *
 * 运行本测试即**产出** `compat/interop-vnext/metadata/android-vnext.json`（路径从当前
 * 工作目录向上搜索 `compat/interop-vnext`，也可用 `-Dinterop.metadataOut=<file>` 指定），
 * 随后由 `tools/interop/check-contract.mjs --mode parity` 比对。元数据全部来自
 * [BackupTableCatalog] 与真实 Kotlin 枚举，**不是**从 contract.json 复制。
 */
class ContractMetadataTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resolveInteropDirectory(): File? {
        System.getProperty("interop.metadataOut")?.let { explicit ->
            val file = File(explicit).absoluteFile
            file.parentFile?.mkdirs()
            return file.parentFile
        }
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val current = directory ?: return@repeat
            val candidate = File(current, "compat/interop-vnext/contract.json")
            if (candidate.isFile) return candidate.parentFile
            // 同仓不同工程时互操作资料在兄弟目录里（本工作区就是这种布局）：
            // 逐个子目录搜索，而不是写死某个绝对路径。
            current.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.forEach { child ->
                    val nested = File(child, "compat/interop-vnext/contract.json")
                    if (nested.isFile) return nested.parentFile
                }
            directory = current.parentFile
        }
        return null
    }

    private fun outputFile(): File? {
        val override = System.getProperty("interop.metadataOut")
        val directory = resolveInteropDirectory() ?: return null
        return if (override != null) File(override).absoluteFile else File(directory, "metadata/android-vnext.json")
    }

    @Test
    fun `emitted metadata is reproducible and complete`() {
        val text = ContractMetadata.renderToString("1.8.5")
        val root = json.parseToJsonElement(text) as JsonObject
        assertEquals(15, (root["portableSchemaVersion"] as JsonPrimitive).content.toInt())
        assertEquals(4, (root["templateFormatVersion"] as JsonPrimitive).content.toInt())
        val tables = root["tables"] as JsonArray
        val names = tables.map { ((it as JsonObject)["name"] as JsonPrimitive).content }
        // 契约表顺序 + 两张本机表。
        assertEquals(26, names.size)
        assertEquals("owners", names.first())
        assertEquals("account_categories", names[2])
        assertEquals("task_progress_events", names[17])
        assertEquals("reminder_settings", names[23])

        val enums = root["enums"] as JsonObject
        assertEquals(17, enums.size)
        val defaults = root["defaults"] as JsonObject
        assertTrue(defaults.containsKey("task_occurrences.backfilled"))
        assertTrue(defaults.containsKey("hosting_sessions.periodId"))

        // 退役列绝不出现：它们只在读取历史文件时被接受。
        val templateTable = tables.map { it as JsonObject }
            .single { (it["name"] as JsonPrimitive).content == "game_templates" }
        val templateColumns = (templateTable["columns"] as JsonArray)
            .map { ((it as JsonObject)["name"] as JsonPrimitive).content }
        assertFalse(templateColumns.contains("serviceMode"))
        assertFalse(templateColumns.contains("serviceCount"))
    }

    @Test
    fun `metadata can be written for the interop checker`() {
        val target = outputFile()
        val text = ContractMetadata.renderToString("1.8.5")
        if (target == null) {
            // 没有同仓 compat 目录时仍然验证内容，不会假装写入成功。
            assertTrue(text.contains("\"platform\": \"android\""))
            return
        }
        target.parentFile?.mkdirs()
        target.writeText(text + "\n")
        assertTrue(target.isFile)
        assertTrue(target.length() > 1000)
    }
}
