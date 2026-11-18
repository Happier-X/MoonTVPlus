package com.moontvplus.phone.update

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

class GithubUpdateSourceTest {
    @get:Rule val folder = TemporaryFolder()
    private val assetUrl = "https://github.com/Happier-X/MoonTVPlus/releases/download/client-v1.9.5/moontvplus-phone-1.9.5-release-signed.apk"
    private fun source(handler: (Interceptor.Chain) -> Response) = GithubUpdateSource(
        OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Authorization"))
            assertNull(chain.request().header("Cookie"))
            handler(chain)
        }.build(),
    )
    private fun reply(chain: Interceptor.Chain, body: String, code: Int = 200, next: Boolean = false, location: String? = null): Response {
        return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("test").code(code)
            .body(body.toResponseBody("application/json".toMediaType())).apply {
                if (next) header("Link", "<https://api.github.com/repos/Happier-X/MoonTVPlus/releases?page=2>; rel=\"next\"")
                if (location != null) header("Location", location)
            }.build()
    }
    private fun releaseJson(version: String) = """[{"tag_name":"client-v$version","assets":[{"name":"moontvplus-phone-$version-release-signed.apk","browser_download_url":"${assetUrl.replace("1.9.5", version)}","size":3}]}]"""

    @Test fun followsPaginationInsteadOfLatestEndpoint() = runBlocking {
        val paths = mutableListOf<String>()
        val service = source { chain ->
            paths += chain.request().url.encodedPath
            if (chain.request().url.queryParameter("page") == "1") reply(chain, releaseJson("1.9.5"), next = true)
            else reply(chain, releaseJson("1.10.0"))
        }
        assertEquals("1.10.0", service.check("1.9.4")?.version)
        assertEquals(2, paths.size)
        assertTrue(paths.none { it.endsWith("latest") })
    }

    @Test fun latestAndNoApplicableAssetAreValidEmptyResults() = runBlocking {
        assertNull(source { reply(it, releaseJson("1.9.4")) }.check("1.9.4"))
        assertNull(source { reply(it, """[{"tag_name":"client-v1.9.9","assets":[]}]""") }.check("1.9.4"))
    }

    @Test fun rateLimitAndNetworkFailureAreActionable() {
        for (status in listOf(403, 429)) {
            val error = assertThrows(IOException::class.java) { runBlocking { source { reply(it, "{}", status) }.check("1.9.4") } }
            assertTrue(error.message!!.contains("频率"))
        }
        assertThrows(IOException::class.java) { runBlocking { source { throw IOException("offline") }.check("1.9.4") } }
    }

    @Test fun downloadsWithProgressAndVerifiesHashAndLength() = runBlocking {
        val progress = mutableListOf<Pair<Long, Long>>()
        val hash = MessageDigest.getInstance("SHA-256").digest("apk".toByteArray()).joinToString("") { "%02x".format(it) }
        val update = ClientUpdate("1.9.5", "notes", ReleaseAsset("moontvplus-phone-1.9.5-release-signed.apk", assetUrl, 3, "sha256:$hash"))
        val file = folder.newFile()
        assertEquals(hash, source { reply(it, "apk") }.download(update, file) { done, total -> progress += done to total })
        assertEquals("apk", file.readText())
        assertEquals(3L to 3L, progress.last())
        assertThrows(IOException::class.java) { runBlocking {
            source { reply(it, "apk") }.download(update.copy(apk = update.apk.copy(size = 4)), file) { _, _ -> }
        } }
        assertThrows(IOException::class.java) { runBlocking {
            source { reply(it, "bad") }.download(update, file) { _, _ -> }
        } }
        Unit
    }

    @Test fun rejectsRedirectToUntrustedHostBeforeRequestingIt() {
        var requests = 0
        val service = source { chain -> requests++; reply(chain, "", 302, location = "https://evil.test/file.apk") }
        val update = ClientUpdate("1.9.5", "", ReleaseAsset("app.apk", assetUrl, 3))
        assertThrows(IOException::class.java) { runBlocking { service.download(update, folder.newFile()) { _, _ -> } } }
        assertEquals(1, requests)
    }
}
