package com.iptv.tv.update

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateCheckerTest {
    private val remote = UpdateChecker.RemoteVersion(
        versionCode = 40,
        versionName = "1.2.0",
        apk = mapOf("browser" to "https://x/browser.apk", "deetv" to "https://x/deetv.apk"),
    )

    @Test
    fun aNewerCodeOffersTheEditionsOwnDownload() {
        val result = assertIs<UpdateChecker.Result.Available>(UpdateChecker.evaluate(remote, installedCode = 39, edition = "deetv"))
        assertEquals("https://x/deetv.apk", result.apkUrl)
    }

    @Test
    fun theSameOrOlderCodeIsUpToDate() {
        assertIs<UpdateChecker.Result.UpToDate>(UpdateChecker.evaluate(remote, installedCode = 40, edition = "deetv"))
        assertIs<UpdateChecker.Result.UpToDate>(UpdateChecker.evaluate(remote, installedCode = 41, edition = "browser"))
    }

    @Test
    fun anUnknownEditionFallsBackToAnyDownloadAndNoDownloadFails() {
        val result = assertIs<UpdateChecker.Result.Available>(UpdateChecker.evaluate(remote, installedCode = 1, edition = "other"))
        assertEquals("https://x/browser.apk", result.apkUrl)
        assertIs<UpdateChecker.Result.Failed>(UpdateChecker.evaluate(remote.copy(apk = emptyMap()), installedCode = 1, edition = "deetv"))
    }

    @Test
    fun versionJsonParsesAndIgnoresExtraFields() {
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<UpdateChecker.RemoteVersion>(
            """{"versionCode": 39, "versionName": "1.1.1", "apk": {"deetv": "https://x/d.apk"}, "notes": "hello", "extra": 1}""",
        )
        assertEquals(39, parsed.versionCode)
        assertEquals("hello", parsed.notes)
        assertEquals("https://x/d.apk", parsed.apk["deetv"])
    }
}
