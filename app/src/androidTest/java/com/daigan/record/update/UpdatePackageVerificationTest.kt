package com.daigan.record.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daigan.record.BuildConfig
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdatePackageVerificationTest {
    @Test fun realInstalledArchiveMatchesManifestAndSignerButRejectsWrongMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.applicationInfo.sourceDir)
        val release = ReleaseInfo(context.packageName,BuildConfig.VERSION_CODE,BuildConfig.VERSION_NAME,"",file.length(),""," ")
        verifyUpdatePackage(context,file,release)
        assertTrue(runCatching { verifyUpdatePackage(context,file,release.copy(versionCode=release.versionCode+1)) }.isFailure)
        assertTrue(runCatching { verifyUpdatePackage(context,file,release.copy(packageName="other.app")) }.isFailure)
    }
}
