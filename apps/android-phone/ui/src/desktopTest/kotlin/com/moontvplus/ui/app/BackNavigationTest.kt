package com.moontvplus.ui.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.moontvplus.phone.core.network.ServerAddress
import com.moontvplus.phone.core.platform.Platform
import com.moontvplus.phone.core.platform.PlatformInfo
import com.moontvplus.phone.di.AppGraph
import com.moontvplus.ui.model.MainTab
import com.moontvplus.ui.model.ShellScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackNavigationTest {
    @get:Rule val ui = createComposeRule()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controller: MoonTvController
    private val searchStarted = CountDownLatch(1)
    private val finishSearch = CountDownLatch(1)
    private val searchReturned = CountDownLatch(1)
    private val dataDir = Files.createTempDirectory("moontv-back-test").toFile()

    @Before fun setup() {
        Platform.init(object : PlatformInfo {
            override val appDataDir = dataDir.absolutePath
            override val userAgent = "MoonTVPlus navigation test"
            override val debug = false
            override val deviceLabel = "test"
            override val platformName = "Desktop"
        })
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.requestUrl?.encodedPath) {
                    "/api/search" -> {
                        searchStarted.countDown()
                        finishSearch.await(10, TimeUnit.SECONDS)
                        searchReturned.countDown()
                        """{"results":[$video]}"""
                    }
                    "/api/source-detail" -> video
                    "/api/favorites", "/api/playrecords" -> "{}"
                    else -> "{}"
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        val graph = AppGraph()
        graph.serverProvider.set(ServerAddress.parseStored(server.url("/").toString()))
        controller = MoonTvController(graph, scope)
        ui.setContent { MoonTvShell(controller, darkTheme = false) }
    }

    @After fun teardown() {
        finishSearch.countDown()
        scope.cancel()
        server.shutdown()
        dataDir.deleteRecursively()
    }

    @Test fun detailBackButtonReturnsHome() {
        ui.runOnIdle { controller.openDetail("test", "42", "测试影片") }
        ui.onNodeWithContentDescription("返回").performClick()
        ui.runOnIdle { assertEquals(ShellScreen.Main, controller.screen) }
    }

    @Test fun libraryBackRestoresDetail() {
        ui.runOnIdle { controller.openDetail("test", "42", "测试影片") }
        ui.waitUntil(10_000) { !controller.detail.loading }
        val detail = controller.detail
        ui.runOnIdle { controller.openLibrary() }
        ui.onNodeWithContentDescription("返回").performClick()
        ui.runOnIdle {
            assertIs<ShellScreen.Detail>(controller.screen)
            assertEquals(detail, controller.detail)
        }
    }

    @Test fun searchCompletionMustNotReopenPageAfterBack() {
        ui.runOnIdle { controller.openByTitle("测试影片") }
        check(searchStarted.await(10, TimeUnit.SECONDS))
        ui.onNodeWithContentDescription("返回").performClick()
        ui.runOnIdle { assertEquals(ShellScreen.Main, controller.screen) }
        finishSearch.countDown()
        check(searchReturned.await(10, TimeUnit.SECONDS))
        // 给响应回到 UI 线程、并可能触发的详情请求留出时间
        Thread.sleep(1500)
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(ShellScreen.Main, controller.screen) }
    }

    @Test fun sectionTabBackReturnsToPreviousTab() {
        ui.runOnIdle { controller.openSectionTab(MainTab.MOVIE) }
        ui.runOnIdle {
            assertEquals(MainTab.MOVIE, controller.tab)
            assertEquals(ShellScreen.Main, controller.screen)
        }
        check(ui.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().isEmpty())
        // 顶栏标题与底栏都出现「电影」，说明界面真的切到了分类页
        check(ui.onAllNodesWithText("电影", useUnmergedTree = true).fetchSemanticsNodes().size >= 2)
        ui.runOnIdle { controller.back() }
        ui.runOnIdle { assertEquals(MainTab.HOME, controller.tab) }
    }

    private companion object {
        const val video = """{"id":"42","source":"test","title":"测试影片","episodes":["https://example.invalid/test.m3u8"],"episodes_titles":["正片"],"year":"2026"}"""
    }
}
