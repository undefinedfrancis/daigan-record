package com.daigan.record.update

import com.daigan.record.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ReleaseInfo(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
)

class UpdateClient(endpoint: String = BuildConfig.UPDATE_SERVICE_URL) {
    private val baseUrl = endpoint.trimEnd('/')
    fun configured(): Boolean = baseUrl.isNotBlank()

    fun check(): ReleaseInfo {
        require(configured()) { "尚未配置更新服务" }
        val connection = URL("$baseUrl/v1/check").openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.requestMethod = "GET"
        try {
            require(connection.responseCode in 200..299) { "更新服务暂时不可用" }
            val root = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
            return ReleaseInfo(
                packageName = root["packageName"]!!.jsonPrimitive.content,
                versionCode = root["versionCode"]!!.jsonPrimitive.content.toInt(),
                versionName = root["versionName"]!!.jsonPrimitive.content,
                notes = root["notes"]!!.jsonPrimitive.content,
                sizeBytes = root["sizeBytes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                sha256 = root["sha256"]!!.jsonPrimitive.content,
                downloadUrl = root["downloadUrl"]!!.jsonPrimitive.content,
            ).also { info ->
                require(info.packageName == BuildConfig.APPLICATION_ID && info.versionCode > 0 && info.versionName.isNotBlank()) { "更新信息与本应用不匹配" }
                require(info.sizeBytes >= 0 && info.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "更新信息缺少有效校验值" }
            }
        } finally { connection.disconnect() }
    }

    /** A failed/partial download is never exposed under an installable APK path. */
    fun download(info: ReleaseInfo, destination: File, onProgress: (Long, Long) -> Unit) {
        require(info.packageName == BuildConfig.APPLICATION_ID) { "更新包不是本应用" }
        require(info.versionCode > BuildConfig.VERSION_CODE) { "没有可安装的新版本" }
        require(info.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "更新包缺少校验值" }
        val endpoint = if (info.downloadUrl.startsWith("http://") || info.downloadUrl.startsWith("https://")) info.downloadUrl else "$baseUrl/${info.downloadUrl.trimStart('/')}"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 30_000
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.delete()
        partial.delete()
        try {
            require(connection.responseCode in 200..299) { "更新包下载失败" }
            val total = info.sizeBytes.takeIf { it > 0 } ?: connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    done += count
                    require(info.sizeBytes <= 0 || done <= info.sizeBytes) { "更新包大小与版本信息不符" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    onProgress(done, total)
                }
            } }
            require(info.sizeBytes <= 0 || done == info.sizeBytes) { "更新包大小与版本信息不符" }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(info.sha256, ignoreCase = true)) { "更新包校验失败" }
            check(partial.renameTo(destination)) { "无法保存已验证的更新包" }
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }
}
