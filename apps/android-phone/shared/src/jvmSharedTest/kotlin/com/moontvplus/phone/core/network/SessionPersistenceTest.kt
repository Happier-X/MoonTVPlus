package com.moontvplus.phone.core.network

import com.moontvplus.phone.core.model.AuthInfo
import com.moontvplus.phone.core.model.AuthSession
import com.moontvplus.phone.core.platform.Platform
import com.moontvplus.phone.core.platform.PlatformInfo
import com.moontvplus.phone.core.storage.SessionStore
import com.moontvplus.phone.core.storage.SettingsStore
import com.moontvplus.phone.core.storage.TextFileStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionPersistenceTest {
    private lateinit var root: File
    private lateinit var settings: SettingsStore
    private lateinit var sessions: SessionManager
    private lateinit var store: SessionStore
    private lateinit var server: MockWebServer
    private val json = com.moontvplus.phone.di.AppGraph.defaultJson()

    @Before fun setup() {
        root = File(System.getProperty("java.io.tmpdir"), "moontv-session-test-" + System.nanoTime())
        root.mkdirs()
        Platform.init(object : PlatformInfo {
            override val appDataDir = root.absolutePath
            override val userAgent = "test"
            override val debug = false
            override val deviceLabel = "test"
            override val platformName = "test"
        })
        val fileStore = TextFileStore(root = root.absolutePath)
        settings = SettingsStore(fileStore, json)
        store = SessionStore(fileStore, json)
        server = MockWebServer().apply { start() }
        val provider = ServerProvider()
        provider.set(ServerAddress.parseStored(server.url("/").toString()))
        val resolver = ApiUrlResolver(provider)
        sessions = SessionManager(store, resolver, json, OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build())
    }

    @After fun teardown() {
        server.shutdown()
        root.deleteRecursively()
    }

    @Test fun credentialsStayOutOfSessionFileAndVaultRestoresAutoLogin(): Unit = runBlocking {
        settings.saveServer(server.url("/").toString(), "Site", "1.9.4", "redis")
        sessions.save(
            AuthSession(
                token = "token-1",
                auth = AuthInfo(role = "owner", username = "tester"),
                storageType = "redis",
                savedUsername = "tester",
                savedPassword = "secret",
            )
        )

        // 模拟进程重启：清空内存态，只保留磁盘文件
        val fileStore = TextFileStore(root = root.absolutePath)
        val reloadedSettings = SettingsStore(fileStore, json)
        val reloadedSessionStore = SessionStore(fileStore, json)
        val reloadedSessions = SessionManager(
            reloadedSessionStore,
            ApiUrlResolver(ServerProvider().apply {
                set(ServerAddress.parseStored(reloadedSettings.snapshot().serverAddress))
            }),
            json,
            OkHttpClient(),
        )
        reloadedSettings.load()
        reloadedSessions.load()

        val restarted = reloadedSessions.session.value
        assertNotNull(restarted)
        // session.json 不落明文凭据
        assertNull(restarted.savedUsername)
        assertNull(restarted.savedPassword)
        val raw = fileStore.read("session.json").orEmpty()
        assertFalse(raw.contains("secret"))
        assertFalse(raw.contains("savedPassword"))

        // 启动时由 Keystore 侧的 CredentialVault 把凭据注回内存会话
        val vault = com.moontvplus.phone.core.storage.InMemoryCredentialVault()
        vault.save("tester", "secret")
        val remembered = vault.load()!!
        reloadedSessions.save(
            restarted.copy(
                savedUsername = remembered.username,
                savedPassword = remembered.password,
            )
        )
        assertTrue(reloadedSessions.session.value!!.canAutoLogin)
        assertTrue(reloadedSettings.snapshot().serverAddress!!.contains(server.hostName))
    }

    @Test fun unknownStorageTypeStillRefreshesToken(): Unit = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"ok":true,"token":"token-2","auth":{"role":"owner","username":"tester"}}"""))
        sessions.save(AuthSession(token = "token-old", auth = AuthInfo(role = "owner"), storageType = null))
        assertTrue(sessions.ensureFresh())
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertTrue(sessions.session.value?.token == "token-2")
    }

    @Test fun logoutClearsSavedCredentials(): Unit = runBlocking {
        settings.saveCredentials("tester", "secret")
        settings.clearCredentials()
        assertNull(settings.snapshot().savedUsername)
        assertNull(settings.snapshot().savedPassword)
        assertFalse(settings.snapshot().legacyCookieMigrationDone)
    }
}
