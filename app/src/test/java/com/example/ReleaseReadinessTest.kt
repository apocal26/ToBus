package com.example

import com.example.data.network.AppUpdateChecker
import com.example.data.network.AppUpdateDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReleaseReadinessTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun releaseVersionAndPackageIdentityAreAligned() {
        assertEquals("com.tobus", BuildConfig.APPLICATION_ID)
        assertEquals("1.1.0", BuildConfig.VERSION_NAME)
        assertTrue(
            "Expected monotonic versionCode >= 10100000, got ${BuildConfig.VERSION_CODE}",
            BuildConfig.VERSION_CODE >= 10_100_000,
        )
    }

    @Test
    fun updaterVersionComparisonWorksForReleaseSeries() {
        assertFalse(AppUpdateChecker.isRemoteNewer("1.1.0", "v1.0.4"))
        assertFalse(AppUpdateChecker.isRemoteNewer("1.1.0", "v1.1.0"))
        assertTrue(AppUpdateChecker.isRemoteNewer("1.1.0", "v1.1.1"))
        assertTrue(AppUpdateChecker.isRemoteNewer("1.1.0", "v1.2.0"))
        assertTrue(AppUpdateChecker.isRemoteNewer("1.1.0", "v2.0.0"))
    }

    @Test
    fun updaterValidatesHostsFilenamesAndApkHeader() {
        assertTrue(AppUpdateDownloader.isAllowedDownloadHost("github.com"))
        assertTrue(AppUpdateDownloader.isAllowedDownloadHost("release-assets.githubusercontent.com"))
        assertFalse(AppUpdateDownloader.isAllowedDownloadHost("evil-example.com"))

        assertEquals("ToBus-v1.1.0.apk", AppUpdateDownloader.sanitizeApkFileName("v1.1.0"))
        assertEquals("ToBus-v1.1.0_hotfix.apk", AppUpdateDownloader.sanitizeApkFileName("v1.1.0/hotfix"))

        val validApk = tempFolder.newFile("valid.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))
        }
        val corruptApk = tempFolder.newFile("corrupt.apk").apply {
            writeText("<html>not an apk</html>")
        }
        assertTrue(AppUpdateDownloader.hasValidApkHeader(validApk))
        assertFalse(AppUpdateDownloader.hasValidApkHeader(corruptApk))
    }
}
