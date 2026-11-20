package com.moontvplus.phone.player

import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.SearchResponse
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.core.storage.EpisodeSkip
import com.moontvplus.phone.core.storage.PlayerPreferencesStore
import com.moontvplus.phone.core.storage.SkipSegment
import com.moontvplus.phone.core.storage.TextFileStore
import com.moontvplus.phone.data.media.EpisodePlan
import com.moontvplus.phone.playback.PlaybackController
import com.moontvplus.phone.playback.PlaybackEngine
import com.moontvplus.phone.playback.PlaybackRequest
import com.moontvplus.phone.playback.PlaybackState
import com.moontvplus.phone.playback.PlaybackStatus
import com.moontvplus.phone.playback.PlayerCapabilities
import com.moontvplus.phone.subtitles.SubtitleRepository
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.FileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionTest {

    private val dispatcher = StandardTestDispatcher()
    private val player = FakePlayer()
    private val details = listOf(source("preferred", 3), source("backup", 3))
    private val api = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MoonApi::class.java)) { _, method, args ->
        when (method.name) {
            "sourceDetail" -> Response.success(details.single { it.source == args[0] })
            "search" -> Response.success(SearchResponse(details))
            "savePlayRecord" -> Response.success(Unit)
            else -> error(method.name)
        }
    } as MoonApi

    @Before
    fun mainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    private fun preferencesStore(): PlayerPreferencesStore {
        val dir = Files.createTempDirectory("player-prefs").toFile()
        return PlayerPreferencesStore(TextFileStore(FileSystem.SYSTEM, dir.absolutePath), Json { ignoreUnknownKeys = true })
    }

    private fun engine() = object : PlaybackEngine {
        override val supportsInlinePlayback = true
        override val capabilities = PlayerCapabilities(
            inlinePlayback = true,
            speedControl = true,
            trackSelection = true,
            subtitles = true,
            externalSubtitles = true,
        )
        override fun create(): PlaybackController = player
    }

    private fun session(store: PlayerPreferencesStore? = null, subtitles: SubtitleRepository? = null) =
        PlayerSession(
            api = api,
            serverProvider = ServerProvider(),
            engine = engine(),
            preferencesStore = store,
            subtitleRepository = subtitles,
            scope = kotlinx.coroutines.CoroutineScope(dispatcher + kotlinx.coroutines.SupervisorJob()),
        )

    /** 真实 IO（文件缓存/网络）不会随虚拟时间推进，这里做有界等待。 */
    private fun kotlinx.coroutines.test.TestScope.settle(condition: () -> Boolean) {
        var waited = 0
        while (!condition() && waited < 3_000) {
            advanceUntilIdle()
            Thread.sleep(5)
            waited += 5
        }
        advanceUntilIdle()
    }

    private fun source(name: String, count: Int) = SearchResult(
        source = name,
        id = "1",
        title = "Fixture",
        weight = 1.0,
        doubanId = 11,
        episodes = List(count) { "https://fixture.test/$name/${it + 1}.mp4" },
    )

    @Test
    fun endOfEpisodeStartsAutoNextCountdownThenAdvances() = runTest(dispatcher) {
        player.available += "preferred"
        val session = session()
        session.load("preferred", "1", "Fixture", 1, 0)
        advanceUntilIdle()
        assertEquals(1, session.state.value.index)

        player.emit(PlaybackStatus.ENDED, positionMs = 60_000)
        runCurrent()
        assertTrue("播放结束应开始倒计时", session.state.value.autoNext.visible)

        advanceTimeBy(6_000)
        advanceUntilIdle()
        assertEquals("倒计时结束后应自动进入下一集", 2, session.state.value.index)
        assertEquals(false, session.state.value.autoNext.visible)
    }

    @Test
    fun cancelAutoNextKeepsCurrentEpisode() = runTest(dispatcher) {
        player.available += "preferred"
        val session = session()
        session.load("preferred", "1", "Fixture", 1, 0)
        advanceUntilIdle()
        player.emit(PlaybackStatus.ENDED, positionMs = 60_000)
        runCurrent()
        session.cancelAutoNext()
        advanceTimeBy(10_000)
        assertEquals(1, session.state.value.index)
    }

    @Test
    fun autoSkipIntroSeeksToRememberedEnd() = runTest(dispatcher) {
        val store = preferencesStore()
        store.update { prefs ->
            prefs.copy(skips = mapOf("preferred+1#1" to EpisodeSkip(intro = SkipSegment(0L, 90_000L))))
        }
        player.available += "preferred"
        val session = session(store)
        settle { session.preferences.value.skips.isNotEmpty() }
        session.load("preferred", "1", "Fixture", 1, 0)
        advanceUntilIdle()

        player.emit(PlaybackStatus.READY, positionMs = 10_000, durationMs = 1_440_000)
        settle { player.seeks.contains(90_000L) }
        assertEquals("片头区间内应自动跳到片头结束", 90_000L, player.seeks.last())
    }

    @Test
    fun markIntroHereRemembersSegmentForTheEpisode() = runTest(dispatcher) {
        val store = preferencesStore()
        player.available += "preferred"
        val session = session(store)
        settle { true }
        session.load("preferred", "1", "Fixture", 1, 0)
        advanceUntilIdle()
        player.emit(PlaybackStatus.READY, positionMs = 30_000, durationMs = 1_440_000)
        advanceUntilIdle()

        session.markIntroHere()
        advanceUntilIdle()
        assertEquals(SkipSegment(0L, 30_000L), session.state.value.intro)
        assertNotNull(session.preferences.value.skips["preferred+1#1"])
    }

    @Test
    fun danmakuWithoutCredentialsReportsConfigurationInsteadOfBlockingPlayback() = runTest(dispatcher) {
        player.available += "preferred"
        val session = session()
        session.load("preferred", "1", "Fixture", 1, 0)
        advanceUntilIdle()
        assertEquals(PlaybackStatus.READY, session.playback.value.status)
        assertEquals(false, session.danmaku.value.configured)
        assertTrue(session.danmaku.value.message.orEmpty().contains("未配置"))
        assertTrue(session.danmaku.value.comments.isEmpty())
    }

    @Test
    fun remoteSubtitleIsLoadedParsedAndSelected() = runTest(dispatcher) {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setBody(
                """
                1
                00:00:01,000 --> 00:00:03,000
                外挂字幕内容
                """.trimIndent(),
            ),
        )
        player.available += "preferred"
        val repository = SubtitleRepository(
            client = OkHttpClient(),
            store = TextFileStore(FileSystem.SYSTEM, Files.createTempDirectory("subs").toFile().absolutePath),
            ioDispatcher = dispatcher,
        )
        val session = session(subtitles = repository)
        session.load("preferred", "1", "Fixture", 1, 0)
        advanceUntilIdle()
        player.emit(PlaybackStatus.READY, positionMs = 1_500, durationMs = 60_000)
        session.addSubtitle(server.url("/external.srt").toString())
        settle { session.subtitle.value.activeExternalId != null }

        val subtitle = session.subtitle.value
        assertNotNull(subtitle.activeExternalId)
        assertEquals("外挂字幕内容", subtitle.cueAt(1_500)?.text)
        server.shutdown()
    }

    @Test
    fun speedBoostIsTemporaryAndRestoresBaseSpeed() = runTest(dispatcher) {
        player.available += "preferred"
        val session = session()
        session.load("preferred", "1", "Fixture", 1, 0)
        advanceUntilIdle()
        session.setSpeed(1.5f)
        assertEquals(1.5f, player.lastSpeed)
        session.beginSpeedBoost()
        assertEquals(3f, player.lastSpeed)
        session.endSpeedBoost()
        assertEquals(1.5f, player.lastSpeed)
    }

    @Test
    fun pictureInPictureCapabilityRequiresPlatform() = runTest(dispatcher) {
        val session = session()
        assertEquals(false, session.capabilities.pictureInPicture)
        assertEquals(false, session.capabilities.brightnessGesture)
        // 投屏能力取决于是否注入了 DLNA 客户端
        assertEquals(false, session.capabilities.casting)
        assertEquals(false, session.state.value.castSupported)
    }

    private class FakePlayer : PlaybackController {
        override val state = MutableStateFlow(PlaybackState())
        val available = mutableSetOf<String>()
        val requests = mutableListOf<PlaybackRequest>()
        val seeks = mutableListOf<Long>()
        var lastSpeed = 1f

        fun emit(status: PlaybackStatus, positionMs: Long = 0, durationMs: Long = 0) {
            state.value = state.value.copy(status = status, positionMs = positionMs, durationMs = durationMs)
        }

        override fun load(request: PlaybackRequest) {
            requests += request
            val source = request.url.substringAfter("fixture.test/").substringBefore('/')
            state.value = if (source in available) {
                PlaybackState(PlaybackStatus.READY, request.startPositionMs, 60_000, isPlaying = true)
            } else {
                PlaybackState(PlaybackStatus.ERROR, error = "test error")
            }
        }

        override fun play() {
            state.value = state.value.copy(isPlaying = true)
        }

        override fun pause() {
            state.value = state.value.copy(isPlaying = false)
        }

        override fun stop() {
            state.value = PlaybackState()
        }

        override fun seekTo(positionMs: Long) {
            seeks += positionMs
            state.value = state.value.copy(positionMs = positionMs)
        }

        override fun setSpeed(speed: Float) {
            lastSpeed = speed
        }

        override fun release() = Unit
        override fun currentPositionMs() = state.value.positionMs
    }
}
