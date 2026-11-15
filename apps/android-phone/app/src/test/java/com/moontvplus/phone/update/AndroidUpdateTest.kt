package com.moontvplus.phone.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.provider.Settings
import androidx.activity.ComponentActivity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidUpdateTest {
    private lateinit var context: Application
    private lateinit var apk: File
    private val update = ClientUpdate("1.9.5", "改进稳定性", ReleaseAsset(
        "moontvplus-phone-1.9.5-release-signed.apk",
        "https://github.com/Happier-X/MoonTVPlus/releases/download/client-v1.9.5/moontvplus-phone-1.9.5-release-signed.apk", 3,
    ))
    private fun info(code: Int, signer: String = "aa", pkg: String = context.packageName, history: Array<Signature>? = null): PackageInfo = PackageInfo().apply {
        packageName = pkg
        versionName = if (code == 14) "1.9.4" else "1.9.5"
        @Suppress("DEPRECATION")
        versionCode = code
        applicationInfo = ApplicationInfo().apply {
            packageName = pkg
            if (android.os.Build.VERSION.SDK_INT >= 24) minSdkVersion = 23
        }
        @Suppress("DEPRECATION")
        signatures = arrayOf(Signature(signer))
        if (android.os.Build.VERSION.SDK_INT >= 28) signingInfo = SigningInfo().also {
            shadowOf(it).setSignatures(arrayOf(Signature(signer)))
            if (history != null) shadowOf(it).setPastSigningCertificates(history)
        }
    }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("client_updates", Context.MODE_PRIVATE).edit().clear().commit()
        val directory = File(context.filesDir, "updates")
        directory.deleteRecursively()
        directory.mkdirs()
        apk = File(directory, "client-update.apk").apply { writeText("apk") }
        val installed = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        @Suppress("DEPRECATION")
        installed.versionCode = 14
        installed.versionName = "1.9.4"
        @Suppress("DEPRECATION")
        installed.signatures = arrayOf(Signature("aa"))
        if (android.os.Build.VERSION.SDK_INT >= 28) installed.signingInfo = info(14).signingInfo
        org.robolectric.shadows.ShadowStatFs.registerStats(directory, 1_000_000, 1_000_000, 1_000_000)
        shadowOf(context.packageManager).setPackageArchiveInfo(apk.absolutePath, info(15))
    }

    private fun seedReady() {
        context.getSharedPreferences("client_updates", Context.MODE_PRIVATE).edit().putString("ready",
            """{"update":${Json.encodeToString(update)},"hash":"${ApkVerifier.sha256(apk)}"}""").commit()
    }

    @Test fun verifiesActualAndroidPackageInfoAndForwardSignerRotation() {
        val verifier = ApkVerifier(context)
        verifier.verify(apk, update)
        shadowOf(context.packageManager).setPackageArchiveInfo(apk.absolutePath, info(15, "bb"))
        assertThrows(IOException::class.java) { verifier.verify(apk, update) }
        shadowOf(context.packageManager).setPackageArchiveInfo(apk.absolutePath,
            info(15, "bb", history = arrayOf(Signature("aa"), Signature("bb"))))
        verifier.verify(apk, update)
        shadowOf(context.packageManager).setPackageArchiveInfo(apk.absolutePath, info(14))
        assertThrows(IOException::class.java) { verifier.verify(apk, update) }
        shadowOf(context.packageManager).setPackageArchiveInfo(apk.absolutePath, info(15, pkg = "other.package"))
        assertThrows(IOException::class.java) { verifier.verify(apk, update) }
    }

    @Test @Config(sdk = [23]) fun legacyAndroidRequiresExactSignerSet() {
        val verifier = ApkVerifier(context)
        verifier.verify(apk, update)
        shadowOf(context.packageManager).setPackageArchiveInfo(apk.absolutePath, info(15, "bb"))
        assertThrows(IOException::class.java) { verifier.verify(apk, update) }
    }

    @Test fun rejectsDebugTamperedAndMissingFiles() {
        val verifier = ApkVerifier(context)
        val debug = info(15).apply { applicationInfo!!.flags = ApplicationInfo.FLAG_DEBUGGABLE }
        shadowOf(context.packageManager).setPackageArchiveInfo(apk.absolutePath, debug)
        assertThrows(IOException::class.java) { verifier.verify(apk, update) }
        shadowOf(context.packageManager).setPackageArchiveInfo(apk.absolutePath, info(15))
        assertThrows(IOException::class.java) { verifier.verify(apk, update, "0".repeat(64)) }
        apk.delete()
        assertThrows(IOException::class.java) { verifier.verify(apk, update) }
    }

    @Test fun restoresCompletedDownloadAndDiscardsPartialAfterProcessDeath() = runTest {
        seedReady()
        val partial = File(apk.parentFile, "client-update.apk.part").apply { writeText("partial") }
        val updater = AppUpdater(context, scope = this, currentVersion = "1.9.4")
        updater.awaitIdle()
        assertEquals(UpdateStage.READY, updater.state.value.stage)
        assertFalse(partial.exists())
        updater.prepareInstall { }
        updater.awaitIdle()
        apk.delete()
        updater.prepareInstall { fail("Missing APK must never be handed to installer") }
        updater.awaitIdle()
        assertEquals(UpdateStage.ERROR, updater.state.value.stage)
        val permissionFlag = AppUpdater(context, scope = this, currentVersion = "1.9.4")
        permissionFlag.awaitIdle()
        permissionFlag.awaitingInstallPermission = true
        val restarted = AppUpdater(context, scope = this, currentVersion = "1.9.4")
        restarted.awaitIdle()
        // The APK was deleted, so a fresh process discards the stale marker instead of resuming.
        assertEquals(UpdateStage.IDLE, restarted.state.value.stage)
        assertFalse(restarted.awaitingInstallPermission)
    }

    @Test fun deniedPermissionCanRetryAndGrantedPermissionResumesWithContentUri() = runTest {
        seedReady()
        val updater = AppUpdater(context, scope = this, currentVersion = "1.9.4")
        updater.awaitIdle()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        // AndroidX FileProvider uses '/' for canonical-path boundaries. Windows Robolectric
        // runs java.io.File with '\\', so inject only the URI boundary on that host.
        val installer = if (File.separatorChar == '\\') UpdateInstaller(activity, updater) { file ->
            assertEquals(apk.canonicalFile, file.canonicalFile)
            val provider = context.packageManager.resolveContentProvider("${context.packageName}.updates", android.content.pm.PackageManager.GET_META_DATA)!!
            assertFalse(provider.exported)
            assertTrue(provider.grantUriPermissions)
            assertTrue(provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS") != 0)
            android.net.Uri.parse("content://${context.packageName}.updates/client_updates/${file.name}")
        } else UpdateInstaller(activity, updater)
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        installer.install()
        updater.awaitIdle()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(activity).nextStartedActivity.action)
        assertTrue(updater.awaitingInstallPermission)
        installer.resume()
        assertFalse(updater.awaitingInstallPermission)
        assertTrue(updater.state.value.message!!.contains("尚未允许"))
        installer.install()
        updater.awaitIdle()
        shadowOf(activity).nextStartedActivity
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        installer.resume()
        updater.awaitIdle()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.updates", intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(updater.awaitingInstallPermission)
        controller.pause().stop().destroy()
    }

    @Test fun downloadCancellationRejectsDuplicateClicksCleansPartialAndAllowsRetry() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val bytes = "x".repeat(128 * 1024)
            val candidate = update.copy(apk = update.apk.copy(size = bytes.length.toLong()))
            val releases = listOf(GithubRelease("client-v1.9.5", assets = listOf(candidate.apk)))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.host == "api.github.com") {
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                        .body(Json.encodeToString(releases).toResponseBody()).build()
                } else chain.proceed(chain.request().newBuilder().url(server.url("/fixture.apk")).build())
            }.build()
            val updater = AppUpdater(context, GithubUpdateSource(client), this, currentVersion = "1.9.4")
            updater.awaitIdle()
            updater.check(manual = true)
            updater.awaitIdle()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(bytes).throttleBody(64 * 1024, 3, java.util.concurrent.TimeUnit.SECONDS))
            updater.download()
            updater.download()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeout(5000) { updater.state.first { it.received > 0 } }
            }
            updater.cancelDownload()
            assertEquals(UpdateStage.CANCELLING, updater.state.value.stage)
            updater.awaitIdle()
            assertEquals(1, server.requestCount)
            assertEquals(UpdateStage.AVAILABLE, updater.state.value.stage)
            val partial = File(apk.parentFile, "client-update.apk.part")
            assertFalse(partial.exists())
            assertFalse(apk.exists())
            shadowOf(context.packageManager).setPackageArchiveInfo(partial.absolutePath, info(15))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(bytes))
            updater.download()
            updater.awaitIdle()
            assertEquals(UpdateStage.READY, updater.state.value.stage)
            assertEquals(2, server.requestCount)
            assertEquals(candidate.apk.size, apk.length())
        } finally { server.shutdown() }
    }

    @Test fun silentCheckIsThrottledIgnoredVersionStaysHiddenAndManualCheckShowsIt() = runTest {
        var calls = 0
        val source = GithubUpdateSource(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body("""[{"tag_name":"client-v1.9.5","body":"说明","assets":[{"name":"${update.apk.name}","browser_download_url":"${update.apk.url}","size":3}]}]""".toResponseBody()).build()
        }.build())
        val updater = AppUpdater(context, source, this, currentVersion = "1.9.4")
        updater.awaitIdle()
        updater.check(manual = false)
        updater.awaitIdle()
        assertTrue(updater.state.value.visible)
        updater.ignore()
        updater.check(manual = false)
        updater.awaitIdle()
        assertEquals(1, calls)
        context.getSharedPreferences("client_updates", Context.MODE_PRIVATE).edit().putLong("last_check", 0).commit()
        updater.check(manual = false)
        updater.awaitIdle()
        assertFalse(updater.state.value.visible)
        updater.check(manual = true)
        updater.awaitIdle()
        assertTrue(updater.state.value.visible)
        assertEquals(3, calls)
    }
}
