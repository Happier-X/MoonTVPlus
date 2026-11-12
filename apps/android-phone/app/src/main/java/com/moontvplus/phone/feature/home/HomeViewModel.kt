package com.moontvplus.phone.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moontvplus.phone.core.model.BangumiCalendarDay
import com.moontvplus.phone.core.model.ContinueItem
import com.moontvplus.phone.core.model.DoubanItem
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.model.TmdbItem
import com.moontvplus.phone.data.repository.HomeCache
import com.moontvplus.phone.data.repository.HomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val imageUrls: com.moontvplus.phone.core.network.ImageUrlBuilder,
) : ViewModel() {

    data class UiState(
        val rows: List<HomeRow> = defaultRows(),
        val refreshing: Boolean = false,
        val firstLoad: Boolean = true,
        val errors: Map<String, String> = emptyMap(),
        val fromCacheOnly: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 先出缓存，首屏不等网络
            val cached = repository.cached()
            if (cached != null) {
                _state.update {
                    it.copy(rows = buildRows(cached, emptyList()), firstLoad = false)
                }
            }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val previous = repository.cached()
            val result = repository.refresh(previous)
            val continueItems = repository.continueWatching().getOrDefault(emptyList())

            _state.update {
                it.copy(
                    rows = buildRows(result.cache, continueItems),
                    refreshing = false,
                    firstLoad = false,
                    errors = result.errors,
                    fromCacheOnly = result.empty,
                )
            }
        }
    }

    private fun buildRows(cache: HomeCache, continueItems: List<ContinueItem>): List<HomeRow> {
        val rows = mapOf(
            HomeRowIds.CONTINUE to HomeRow(
                id = HomeRowIds.CONTINUE,
                title = "继续观看",
                cards = continueItems.map { it.toCard() },
            ),
            HomeRowIds.MOVIES to HomeRow(
                id = HomeRowIds.MOVIES,
                title = "热门电影",
                cards = cache.movies.map { it.toCard() },
            ),
            HomeRowIds.DUANJU to HomeRow(
                id = HomeRowIds.DUANJU,
                title = "热播短剧",
                cards = cache.duanju.map { it.toCard() },
            ),
            HomeRowIds.BANGUMI to HomeRow(
                id = HomeRowIds.BANGUMI,
                title = "今日新番",
                cards = todayAnime(cache.bangumi).map { it.toCard() },
            ),
            HomeRowIds.TV to HomeRow(
                id = HomeRowIds.TV,
                title = "热门剧集",
                cards = cache.tvShows.map { it.toCard() },
            ),
            HomeRowIds.VARIETY to HomeRow(
                id = HomeRowIds.VARIETY,
                title = "热门综艺",
                cards = cache.variety.map { it.toCard() },
            ),
            HomeRowIds.UPCOMING to HomeRow(
                id = HomeRowIds.UPCOMING,
                title = "即将上映",
                cards = cache.upcoming.map { it.toCard() },
            ),
        )
        return HomeRowIds.ORDER.mapNotNull { rows[it] }
            .filter { it.visible }
            // 海报统一走站点图片代理（防盗链 + 自动带鉴权）
            .map { row ->
                row.copy(cards = row.cards.map { it.copy(poster = imageUrls.proxied(it.poster)) })
            }
    }

    /** Bangumi 日历按周几分组，首页只取今天这一组（与网页版一致）。 */
    private fun todayAnime(days: List<BangumiCalendarDay>) = run {
        val weekday = WEEKDAYS[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
        days.firstOrNull { it.weekday?.en.equals(weekday, ignoreCase = true) }
            ?.items
            ?.filter { it.images?.best != null }
            .orEmpty()
    }

    private companion object {
        val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

        fun defaultRows(): List<HomeRow> = HomeRowIds.ORDER.map {
            HomeRow(id = it, title = titleOf(it), loading = true)
        }

        fun titleOf(id: String): String = when (id) {
            HomeRowIds.CONTINUE -> "继续观看"
            HomeRowIds.MOVIES -> "热门电影"
            HomeRowIds.DUANJU -> "热播短剧"
            HomeRowIds.BANGUMI -> "今日新番"
            HomeRowIds.TV -> "热门剧集"
            HomeRowIds.VARIETY -> "热门综艺"
            else -> "即将上映"
        }
    }
}

private fun ContinueItem.toCard() = HomeCard(
    key = key,
    title = record.title,
    poster = record.cover,
    badge = if (record.progress > 0f) "${(record.progress * 100).toInt()}%" else null,
    subtitle = buildString {
        append("第 ${record.index} 集")
        if (record.totalEpisodes > 0) append(" / 共 ${record.totalEpisodes} 集")
    },
    target = HomeTarget.Resume(source, videoId, record.index),
)

private fun DoubanItem.toCard() = HomeCard(
    key = "douban+$id",
    title = title,
    poster = poster,
    badge = rate.takeIf { it.isNotBlank() && it != "0" },
    subtitle = year.takeIf { it.isNotBlank() },
    target = HomeTarget.SourceSearch(
        title = title,
        year = year.takeIf { it.isNotBlank() },
        doubanId = id.toLongOrNull() ?: 0L,
    ),
)

private fun com.moontvplus.phone.core.model.BangumiSubject.toCard() = HomeCard(
    key = "bangumi+$id",
    title = displayTitle,
    poster = images?.best,
    badge = rating?.score?.takeIf { it > 0 }?.let { String.format("%.1f", it) },
    subtitle = year.takeIf { it.isNotBlank() },
    target = HomeTarget.SourceSearch(
        title = displayTitle,
        year = year.takeIf { it.isNotBlank() },
        doubanId = id.toLong(),
    ),
)

private fun SearchResult.toCard() = HomeCard(
    key = key,
    title = title,
    poster = poster,
    badge = vodRemarks?.takeIf { it.isNotBlank() },
    subtitle = when {
        episodes.isNotEmpty() -> "共 ${episodes.size} 集"
        year.isNotBlank() -> year
        else -> sourceName.takeIf { it.isNotBlank() }
    },
    target = HomeTarget.Detail(source, id),
)

private fun TmdbItem.toCard() = HomeCard(
    key = "tmdb+$id",
    title = title,
    poster = posterUrl.takeIf { it.isNotBlank() },
    badge = releaseDate?.takeIf { it.isNotBlank() },
    subtitle = mediaTypeLabel(),
    target = HomeTarget.SourceSearch(
        title = title,
        year = year.takeIf { it.isNotBlank() },
    ),
)

private fun TmdbItem.mediaTypeLabel(): String = when (mediaType) {
    "tv" -> "剧集"
    else -> "电影"
}
