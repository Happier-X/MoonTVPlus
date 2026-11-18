package com.moontvplus.phone.feature.player

import com.moontvplus.phone.core.model.SavePlayRecordRequest
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.SearchResponse
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.playback.PlaybackController
import com.moontvplus.phone.playback.PlaybackEngine
import com.moontvplus.phone.playback.PlaybackRequest
import com.moontvplus.phone.playback.PlaybackState
import com.moontvplus.phone.playback.PlaybackStatus
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val player = FakePlayer()
    private val records = mutableListOf<SavePlayRecordRequest>()
    private val details = listOf(
        source("preferred", 3), source("short", 1, weight = 10.0), source("backup", 3),
        source("remake", 3, douban = 22),
    )
    private val api = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MoonApi::class.java)) { _, method, args ->
        when (method.name) {
            "sourceDetail" -> Response.success(details.single { it.source == args[0] })
            "search" -> Response.success(SearchResponse(details))
            "savePlayRecord" -> {
                records += args[0] as SavePlayRecordRequest
                Response.success(Unit)
            }
            else -> error(method.name)
        }
    } as MoonApi

    @Before fun mainDispatcher() = Dispatchers.setMain(dispatcher)
    @After fun resetDispatcher() = Dispatchers.resetMain()

    @Test fun fallsBackToTheSameEpisodeAtTheOriginalPosition() = runTest(dispatcher) {
        player.available += "backup"
        val vm = model()
        vm.load("preferred", "1", "Fixture", 2, 12_000)
        advanceUntilIdle()
        assertEquals(listOf("preferred/2.mp4", "backup/2.mp4"), player.requests.map { it.url.substringAfter("fixture.test/") })
        assertTrue(player.requests.all { it.startPositionMs == 12_000L })
        assertEquals("backup", vm.state.value.source?.source)
        vm.goTo(3)
        advanceUntilIdle()
        assertEquals(0L, player.requests.last().startPositionMs)
        assertEquals(2, records.single().record.index)
        assertEquals(12.0, records.single().record.playTime, 0.0)
    }

    @Test fun exhaustedSourcesAllowManualRecoveryAndRetry() = runTest(dispatcher) {
        val vm = model()
        vm.load("preferred", "1", "Fixture", 2, 9_000)
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
        player.available += "backup"
        vm.chooseSource(details.single { it.source == "backup" })
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals(9_000L, player.requests.last().startPositionMs)
        vm.retry()
        advanceUntilIdle()
        assertEquals("backup", vm.state.value.source?.source)
    }

    private fun model() = PlayerViewModel(api, ServerProvider(), object : PlaybackEngine {
        override val supportsInlinePlayback = true
        override fun create() = player
    })

    private fun source(name: String, count: Int, weight: Double = 0.0, douban: Long = 11) = SearchResult(
        source = name, id = "1", title = "Fixture", weight = weight, doubanId = douban,
        episodes = List(count) { "https://fixture.test/$name/${it + 1}.mp4" },
    )

    private class FakePlayer : PlaybackController {
        override val state = MutableStateFlow(PlaybackState())
        val available = mutableSetOf<String>()
        val requests = mutableListOf<PlaybackRequest>()
        override fun load(request: PlaybackRequest) {
            requests += request
            val source = request.url.substringAfter("fixture.test/").substringBefore('/')
            state.value = if (source in available) PlaybackState(PlaybackStatus.READY, request.startPositionMs, 60_000)
                else PlaybackState(PlaybackStatus.ERROR, error = "test error")
        }
        override fun stop() { state.value = PlaybackState() }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun release() = Unit
        override fun currentPositionMs() = state.value.positionMs
    }
}
