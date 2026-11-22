package com.moontvplus.phone.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MirrorDownloaderTest {
    @get:Rule val folder = TemporaryFolder()

    private val site = ReleaseAsset(
        "moontvplus-phone-latest.apk",
        "https://tv.370125.xyz/downloads/moontvplus-phone-latest.apk",
        3,
        "sha256:" + "a".repeat(64),
    )
    private val github = ReleaseAsset(
        "moontvplus-phone-1.9.12-release-signed.apk",
        "https://github.com/Happier-X/MoonTVPlus/releases/download/client-v1.9.12/moontvplus-phone-1.9.12-release-signed.apk",
        3,
        "sha256:" + "a".repeat(64),
    )

    private class RecordingDownloader(private val failUrls: Set<String> = emptySet()) : UpdateSource {
        val calls = mutableListOf<String>()
        override suspend fun check(currentVersion: String): ClientUpdate? = null
        override suspend fun download(update: ClientUpdate, destination: File, progress: (Long, Long) -> Unit): String {
            calls += update.apk.url
            if (update.apk.url in failUrls) throw IOException("mirror failed")
            destination.writeText("apk")
            progress(3, 3)
            return "hash"
        }
    }

    @Test fun picksFastestMirror() = runBlocking {
        val downloader = RecordingDownloader()
        val updater = MirrorDownloader(downloader, probe = { url -> if (url == site.url) 1000L else 10L })
        val update = ClientUpdate("1.9.12", "", github, mirrors = listOf(site))
        val file = folder.newFile()
        assertEquals("hash", updater.download(update, file) { _, _ -> })
        assertEquals(listOf(site.url), downloader.calls)
    }

    @Test fun fallsBackWhenFastestMirrorFails() = runBlocking {
        val downloader = RecordingDownloader(failUrls = setOf(site.url))
        val updater = MirrorDownloader(downloader, probe = { url -> if (url == site.url) 1000L else 10L })
        val update = ClientUpdate("1.9.12", "", github, mirrors = listOf(site))
        val file = folder.newFile()
        assertEquals("hash", updater.download(update, file) { _, _ -> })
        assertEquals(listOf(site.url, github.url), downloader.calls)
    }

    @Test fun ignoresUntrustedMirror() = runBlocking {
        val downloader = RecordingDownloader()
        val evil = ReleaseAsset("app.apk", "https://evil.test/app.apk", 3)
        val updater = MirrorDownloader(downloader, probe = { 0L })
        val update = ClientUpdate("1.9.12", "", github, mirrors = listOf(evil))
        updater.download(update, folder.newFile()) { _, _ -> }
        assertEquals(listOf(github.url), downloader.calls)
    }
}