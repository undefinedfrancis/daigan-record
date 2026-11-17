package com.daigan.record.update

import com.daigan.record.BuildConfig
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class UpdateClientTest {
    private fun withServer(bytes:ByteArray, block:(UpdateClient)->Unit) {
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val worker=thread(isDaemon=true) {
            server.accept().use { socket ->
                socket.soTimeout=5000
                val input=socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().use { out ->
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                }
            }
        }
        try { block(UpdateClient("http://127.0.0.1:${server.localPort}")) }
        finally { server.close();worker.join(2000) }
    }
    @Test fun failedHashNeverLeavesAnInstallableFile() = withServer("bad bytes".toByteArray()) { client ->
        val dir=Files.createTempDirectory("update-test").toFile()
        try {
            val file=dir.resolve("candidate.apk")
            val info=ReleaseInfo(BuildConfig.APPLICATION_ID,BuildConfig.VERSION_CODE+1,"next","",9,"0".repeat(64),"/apk")
            assertTrue(runCatching { client.download(info,file) { _,_-> } }.isFailure)
            assertFalse("corrupt download must be removed",file.exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun metadataSizeMustMatchDownloadedFile() = withServer("valid bytes".toByteArray()) { client ->
        val dir=Files.createTempDirectory("update-test").toFile()
        try {
            val bytes="valid bytes".toByteArray()
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val file=dir.resolve("candidate.apk")
            assertTrue(runCatching { client.download(ReleaseInfo(BuildConfig.APPLICATION_ID,BuildConfig.VERSION_CODE+1,"next","",999,hash,"/apk"),file) { _,_-> } }.isFailure)
            assertFalse(file.exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun validDownloadUsesMetadataAndReportsCompletion() = withServer("valid bytes".toByteArray()) { client ->
        val dir=Files.createTempDirectory("update-test").toFile()
        try {
            val bytes="valid bytes".toByteArray()
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val file=dir.resolve("candidate.apk");var done=0L
            client.download(ReleaseInfo(BuildConfig.APPLICATION_ID,BuildConfig.VERSION_CODE+1,"next","",bytes.size.toLong(),hash,"/apk"),file) { n,_->done=n }
            assertArrayEquals(bytes,file.readBytes());assertEquals(bytes.size.toLong(),done)
        } finally { dir.deleteRecursively() }
    }
    @Test fun releaseCheckReadsLocalProtocolMetadata() {
        val body="""{"packageName":"${BuildConfig.APPLICATION_ID}","versionCode":${BuildConfig.VERSION_CODE+1},"versionName":"next","notes":"修复","sizeBytes":12,"sha256":"${"a".repeat(64)}","downloadUrl":"/apk"}""".toByteArray()
        withServer(body) { client ->
            val release=client.check()
            assertEquals(BuildConfig.VERSION_CODE+1,release.versionCode)
            assertEquals("修复",release.notes)
            assertEquals(12L,release.sizeBytes)
        }
    }
    @Test fun releaseCheckRejectsDifferentApplication() {
        val body="""{"packageName":"other.app","versionCode":99,"versionName":"next","notes":"","sha256":"${"a".repeat(64)}","downloadUrl":"/apk"}""".toByteArray()
        withServer(body) { client -> assertTrue(runCatching { client.check() }.isFailure) }
    }

}
