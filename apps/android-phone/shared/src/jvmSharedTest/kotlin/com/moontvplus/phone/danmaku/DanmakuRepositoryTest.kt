package com.moontvplus.phone.danmaku

import com.moontvplus.phone.core.storage.DanmakuPreferences
import com.moontvplus.phone.core.storage.TextFileStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.FileSystem
import okio.ByteString.Companion.encodeUtf8

class DanmakuRepositoryTest {

    private val server = MockWebServer()

    @BeforeTest
    fun setUp() {
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun repository(): DanmakuRepository {
        val dir = Files.createTempDirectory("danmaku-test").toFile()
        return DanmakuRepository(
            client = OkHttpClient(),
            store = TextFileStore(FileSystem.SYSTEM, dir.absolutePath),
            json = Json { ignoreUnknownKeys = true },
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    private val preferences = DanmakuPreferences(appId = "app-id", appSecret = "secret")

    @Test
    fun searchParsesEpisodesAndSignsRequest() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"success":true,"animes":[{"animeId":42,"animeTitle":"测试剧",
                "episodes":[{"episodeId":7,"episodeTitle":"第2话"}]}]}
                """.trimIndent(),
            ),
        )
        val result = repository().search("测试剧", 2, preferences)
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        val episodes = result.getOrThrow()
        assertEquals(1, episodes.size)
        assertEquals(7L, episodes[0].episodeId)

        val request = server.takeRequest()
        assertEquals("app-id", request.getHeader("X-AppId"))
        val timestamp = request.getHeader("X-Timestamp")!!
        val expected = ("app-id" + timestamp + "/api/v2/search/episodes" + "secret")
            .encodeUtf8().sha256().base64()
        assertEquals(expected, request.getHeader("X-Signature"))
        assertTrue(request.path!!.contains("anime=%E6%B5%8B%E8%AF%95%E5%89%A7"))
    }

    @Test
    fun commentsAreCachedAfterFirstFetch() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"count":2,"comments":[
                  {"cid":1,"p":"1.5,1,16777215,0","m":"第一条"},
                  {"cid":2,"p":"2.0,5,16711680,0","m":"顶部"}
                ]}
                """.trimIndent(),
            ),
        )
        val repository = repository()
        val first = repository.comments(7L, preferences)
        assertEquals(2, first.getOrThrow().size)
        assertEquals(1, server.requestCount)

        val second = repository.comments(7L, preferences)
        assertEquals(2, second.getOrThrow().size)
        // 第二次命中本地缓存，不再发请求
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unconfiguredCredentialsFailFast() = runBlocking {
        val result = repository().search("测试剧", 1, DanmakuPreferences())
        assertTrue(result.isFailure)
        assertEquals("弹幕未配置", result.exceptionOrNull()?.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unauthorizedResponseExplainsCredentialProblem() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        val result = repository().search("测试剧", 1, preferences)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("AppId"))
    }

    @Test
    fun bestMatchPrefersTheRequestedEpisodeNumber() {
        val repository = repository()
        val candidates = listOf(
            DanmakuEpisode(1, "测试剧", episodeId = 10, episodeTitle = "第1话"),
            DanmakuEpisode(1, "测试剧", episodeId = 12, episodeTitle = "第3话"),
            DanmakuEpisode(2, "另一部", episodeId = 99, episodeTitle = "第2话"),
        )
        assertEquals(12L, repository.bestMatch(candidates, "测试剧", 3)?.episodeId)
        assertEquals(null, repository.bestMatch(emptyList(), "测试剧", 3))
    }
}
