package com.daigan.record.update

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/** Verify downloaded manifest and signing identity before offering the APK to Android. */
@Suppress("DEPRECATION")
fun verifyUpdatePackage(context: Context, file: File, release: ReleaseInfo) {
    val manager = context.packageManager
    val candidate = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        ?: error("更新包无法读取，请重新下载")
    require(candidate.packageName == context.packageName && candidate.packageName == release.packageName) { "更新包应用标识不匹配" }
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
    require(code == release.versionCode.toLong() && candidate.versionName == release.versionName) { "更新包版本与更新信息不匹配" }
    val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    val expected = installed.signatures.orEmpty().map { it.toCharsString() }.toSet()
    val actual = candidate.signatures.orEmpty().map { it.toCharsString() }.toSet()
    require(expected.isNotEmpty() && expected == actual) { "更新包签名不匹配，无法覆盖安装" }
}
