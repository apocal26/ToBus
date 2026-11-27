package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateAbiSelectionTest {

    private val multiAbiReleaseJson = """
        {
          "tag_name": "v2.0.0",
          "name": "ToBus 2.0.0",
          "html_url": "https://github.com/tobus/ToBus/releases/tag/v2.0.0",
          "draft": false,
          "prerelease": false,
          "body": "## Novità\n- **APK leggeri** per architettura\n- Fix vari",
          "assets": [
            {
              "name": "ToBus-v2.0.0-armeabi-v7a.apk",
              "size": 11500000,
              "browser_download_url": "https://github.com/tobus/ToBus/releases/download/v2.0.0/ToBus-v2.0.0-armeabi-v7a.apk"
            },
            {
              "name": "ToBus-v2.0.0-arm64-v8a.apk",
              "size": 14200000,
              "browser_download_url": "https://github.com/tobus/ToBus/releases/download/v2.0.0/ToBus-v2.0.0-arm64-v8a.apk"
            },
            {
              "name": "ToBus-v2.0.0-universal.apk",
              "size": 43800000,
              "browser_download_url": "https://github.com/tobus/ToBus/releases/download/v2.0.0/ToBus-v2.0.0-universal.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun modernArm64PhonePicksArm64EvenWhenArmv7IsListedFirst() {
        val info = AppUpdateChecker.parseLatestReleaseJson(
            jsonText = multiAbiReleaseJson,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
        )
        assertNotNull(info)
        assertEquals("arm64-v8a", info!!.selectedApkAbi)
        assertEquals(14_200_000L, info.selectedApkSizeBytes)
        assertTrue(info.apkDownloadUrl!!.endsWith("ToBus-v2.0.0-arm64-v8a.apk"))
    }

    @Test
    fun legacy32BitPhonePicksArmeabiV7a() {
        val info = AppUpdateChecker.parseLatestReleaseJson(
            jsonText = multiAbiReleaseJson,
            supportedAbis = listOf("armeabi-v7a", "armeabi"),
        )
        assertNotNull(info)
        assertEquals("armeabi-v7a", info!!.selectedApkAbi)
        assertEquals(11_500_000L, info.selectedApkSizeBytes)
        assertTrue(info.apkDownloadUrl!!.endsWith("ToBus-v2.0.0-armeabi-v7a.apk"))
    }

    @Test
    fun x86_64DeviceFallsBackToUniversalApkWhenNoX86_64SplitExists() {
        val info = AppUpdateChecker.parseLatestReleaseJson(
            jsonText = multiAbiReleaseJson,
            supportedAbis = listOf("x86_64", "x86"),
        )
        assertNotNull(info)
        assertEquals("universal", info!!.selectedApkAbi)
        assertEquals(43_800_000L, info.selectedApkSizeBytes)
        assertTrue(info.apkDownloadUrl!!.endsWith("ToBus-v2.0.0-universal.apk"))
    }

    @Test
    fun legacySingleApkReleaseWithoutAbiTagRemainsFullyCompatible() {
        val legacyJson = """
            {
              "tag_name": "v1.5.0",
              "name": "ToBus 1.5.0",
              "html_url": "https://github.com/tobus/ToBus/releases/tag/v1.5.0",
              "body": "Aggiornamento classico",
              "assets": [
                {
                  "name": "ToBus-v1.5.0.apk",
                  "size": 42000000,
                  "browser_download_url": "https://github.com/tobus/ToBus/releases/download/v1.5.0/ToBus-v1.5.0.apk"
                }
              ]
            }
        """.trimIndent()

        val info = AppUpdateChecker.parseLatestReleaseJson(
            jsonText = legacyJson,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )
        assertNotNull(info)
        assertNull(info!!.selectedApkAbi)
        assertEquals(42_000_000L, info.selectedApkSizeBytes)
        assertTrue(info.apkDownloadUrl!!.endsWith("ToBus-v1.5.0.apk"))
    }

    @Test
    fun incompatibleOnlyReleaseNeverSelectsWrongCpuArchitecture() {
        val armOnlyJson = """
            {
              "tag_name": "v2.1.0",
              "name": "ToBus 2.1.0",
              "html_url": "https://github.com/tobus/ToBus/releases/tag/v2.1.0",
              "body": "Solo arm64",
              "assets": [
                {
                  "name": "ToBus-v2.1.0-arm64-v8a.apk",
                  "size": 14000000,
                  "browser_download_url": "https://github.com/tobus/ToBus/releases/download/v2.1.0/ToBus-v2.1.0-arm64-v8a.apk"
                }
              ]
            }
        """.trimIndent()

        val info = AppUpdateChecker.parseLatestReleaseJson(
            jsonText = armOnlyJson,
            supportedAbis = listOf("x86_64", "x86"),
        )
        assertNotNull(info)
        assertNull(info!!.apkDownloadUrl)
        assertNull(info.selectedApkAbi)
        assertEquals("https://github.com/tobus/ToBus/releases/tag/v2.1.0", info.preferredOpenUrl)
    }

    @Test
    fun detectApkAbiTagDistinguishesX86FromX86_64AndIgnoresUntrustedUrls() {
        assertEquals("x86_64", AppUpdateChecker.detectApkAbiTag("ToBus-v2.0-x86_64.apk"))
        assertEquals("x86_64", AppUpdateChecker.detectApkAbiTag("ToBus-v2.0-x86-64.apk"))
        assertEquals("x86", AppUpdateChecker.detectApkAbiTag("ToBus-v2.0-x86.apk"))
        assertEquals("arm64-v8a", AppUpdateChecker.detectApkAbiTag("ToBus-v2.0-arm64.apk"))
        assertEquals("armeabi-v7a", AppUpdateChecker.detectApkAbiTag("ToBus-v2.0-armv7.apk"))
        assertEquals("universal", AppUpdateChecker.detectApkAbiTag("ToBus-v2.0-universal.apk"))
        assertNull(AppUpdateChecker.detectApkAbiTag("ToBus-v2.0.apk"))

        val selected = AppUpdateChecker.selectBestApkAsset(
            assets = listOf(
                ReleaseAssetCandidate(
                    name = "ToBus-v2.0-arm64-v8a.apk",
                    downloadUrl = "https://evil.example.com/ToBus-v2.0-arm64-v8a.apk",
                    sizeBytes = 14_000_000L,
                ),
                ReleaseAssetCandidate(
                    name = "ToBus-v2.0-universal.apk",
                    downloadUrl = "https://github.com/tobus/ToBus/releases/download/v2.0/ToBus-v2.0-universal.apk",
                    sizeBytes = 43_000_000L,
                ),
            ),
            supportedAbis = listOf("arm64-v8a"),
        )
        assertNotNull(selected)
        assertEquals("universal", selected!!.abiLabel)
    }

    @Test
    fun universalListedFirstStillSelectsLightweightAbiSplit() {
        val selected = AppUpdateChecker.selectBestApkAsset(
            assets = listOf(
                ReleaseAssetCandidate(
                    name = "ToBus-v2.0-universal.apk",
                    downloadUrl = "https://github.com/tobus/ToBus/releases/download/v2.0/ToBus-v2.0-universal.apk",
                    sizeBytes = 43_000_000L,
                ),
                ReleaseAssetCandidate(
                    name = "ToBus-v2.0-arm64-v8a.apk",
                    downloadUrl = "https://github.com/tobus/ToBus/releases/download/v2.0/ToBus-v2.0-arm64-v8a.apk",
                    sizeBytes = 14_000_000L,
                ),
                ReleaseAssetCandidate(
                    name = "ToBus-v2.0-armeabi-v7a.apk",
                    downloadUrl = "https://github.com/tobus/ToBus/releases/download/v2.0/ToBus-v2.0-armeabi-v7a.apk",
                    sizeBytes = 11_500_000L,
                ),
            ),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )
        assertNotNull(selected)
        assertEquals("arm64-v8a", selected!!.abiLabel)
        assertTrue(selected.downloadUrl.endsWith("ToBus-v2.0-arm64-v8a.apk"))
    }
}
