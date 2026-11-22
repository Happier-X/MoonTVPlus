package com.moontvplus.phone.update

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

class SiteUpdateSourceTest {
    @get:Rule val folder = TemporaryFolder()
    private val apkUrl = "https://tv.370125.xyz/downloads/moontvplus-phone-latest.apk"

    private fun source(handler: (Interceptor.Chain) -> Response) = SiteUpdateSource(
        client = OkHttpClient.Builder().addInterceptor { chain -> handler(chain) }.build(),
    )

    private fun reply(chain: Interceptor.Chain, body: String, code: Int = 200): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("test").code(code)
            .body(body.toResponseBody("application/json".toMediaType())).build()

    private fun manifest(version: String = "1.9.10", url: String = apkUrl, sha: String? = null): String {
        val hash = sha?.let { ",\"sha256\":\"$it\"" } ?: ""
        return """{"version":"$version","notes":"镜像更新","apk":{"name":"moontvplus-phone-latest.apk","url":"$url","size":3$hash}}"""
    }

    @Test fun readsNewerSiteManifest() = runBlocking {
        val hash = MessageDigest.getInstance("SHA-256").digest("apk".toByteArray()).joinToString("") { "%02x".format(it) }
        val update = source { reply(it, manifest(sha = hash)) }.check("1.9.9")
        assertEquals("1.9.10", update?.version)
        assertEquals(apkUrl, update?.apk?.url)
        assertEquals("sha256:$hash", update?.apk?.digest)
    }

    @Test fun sameVersionIsLatest() = runBlocking {
        assertNull(source { reply(it, manifest()) }.check("1.9.10"))
    }

    @Test fun rejectsUntrustedManifestUrl() {
        val error = assertThrows(IOException::class.java) {
            runBlocking { source { reply(it, manifest(url = "https://evil.test/app.apk")) }.check("1.9.9") }
        }
        assertTrue(error.message!!.contains("不可信"))
    }

    @Test fun downloadsSiteApkAndVerifiesHash() = runBlocking {
        val hash = MessageDigest.getInstance("SHA-256").digest("apk".toByteArray()).joinToString("") { "%02x".format(it) }
        val update = ClientUpdate(
            "1.9.10", "", ReleaseAsset("moontvplus-phone-latest.apk", apkUrl, 3, "sha256:$hash"),
        )
        val file = folder.newFile()
        assertEquals(hash, source { reply(it, "apk") }.download(update, file) { _, _ -> })
        assertEquals("apk", file.readText())
    }
}