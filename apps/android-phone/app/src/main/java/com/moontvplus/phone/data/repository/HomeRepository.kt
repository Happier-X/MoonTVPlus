package com.moontvplus.phone.data.repository

import android.content.Context
import com.moontvplus.phone.core.model.BangumiCalendarDay
import com.moontvplus.phone.core.model.ContinueItem
import com.moontvplus.phone.core.model.DoubanItem
import com.moontvplus.phone.core.model.PlayRecord
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.model.TmdbItem
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.ServerProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.Response

/** 首页各区块的原始数据；直接用它做缓存，避免再发明一套 DTO。 */
@Serializable
data class HomeCache(
    val savedAt: Long = 0L,
    val movies: List<DoubanItem> = emptyList(),
    val tvShows: List<DoubanItem> = emptyList(),
    val variety: List<DoubanItem> = emptyList(),
    val bangumi: List<BangumiCalendarDay> = emptyList(),
    val duanju: List<SearchResult> = emptyList(),
    val upcoming: List<TmdbItem> = emptyList(),
)

/** 一次刷新的结果：数据 + 每块各自的错误（某块失败时保留上一次的数据）。 */
data class HomeLoadResult(
    val cache: HomeCache,
    val errors: Map<String, String> = emptyMap(),
    /** 完全没有可用数据（首次加载就全失败） */
    val empty: Boolean = false,
)

@Singleton
class HomeRepository @Inject constructor(
    private val api: MoonApi,
    private val json: Json,
    private val serverProvider: ServerProvider,
    @ApplicationContext private val context: Context,
) {

    /** 冷启动先读缓存，先渲染再刷新。 */
    suspend fun cached(): HomeCache? = withContext(Dispatchers.IO) {
        val file = cacheFile() ?: return@withContext null
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(HomeCache.serializer(), file.readText())
        }.getOrNull()?.takeIf { it.savedAt > 0 }
    }

    /**
     * 并行刷新六个区块，任何一块失败只记录它自己的错误，
     * 并用 [previous] 里的旧数据兜住，避免整页变空。
     */
    suspend fun refresh(previous: HomeCache?): HomeLoadResult = coroutineScope {
        val movies = async { safeFetch { api.doubanCategories("movie", "热门", "全部").requireBody().list } }
        val tvShows = async { safeFetch { api.doubanCategories("tv", "tv", "tv").requireBody().list } }
        val variety = async { safeFetch { api.doubanCategories("tv", "show", "show").requireBody().list } }
        val bangumi = async { safeFetch { api.bangumiCalendar().requireBody() } }
        val duanju = async { safeFetch { api.duanjuRecommends().requireBody().data } }
        val upcoming = async { safeFetch { api.tmdbUpcoming().requireBody().data.sortedBy { it.releaseDate ?: "9999" } } }

        val errors = mutableMapOf<String, String>()

        fun <T> pick(
            rowId: String,
            result: Result<List<T>>,
            fallback: () -> List<T>,
        ): List<T> = result.fold(
            onSuccess = { it },
            onFailure = { error ->
                errors[rowId] = error.message ?: "加载失败"
                fallback()
            },
        )

        val cache = HomeCache(
            savedAt = System.currentTimeMillis(),
            movies = pick(HomeRowIds.MOVIES, movies.await()) { previous?.movies.orEmpty() },
            tvShows = pick(HomeRowIds.TV, tvShows.await()) { previous?.tvShows.orEmpty() },
            variety = pick(HomeRowIds.VARIETY, variety.await()) { previous?.variety.orEmpty() },
            bangumi = pick(HomeRowIds.BANGUMI, bangumi.await()) { previous?.bangumi.orEmpty() },
            duanju = pick(HomeRowIds.DUANJU, duanju.await()) { previous?.duanju.orEmpty() },
            upcoming = pick(HomeRowIds.UPCOMING, upcoming.await()) { previous?.upcoming.orEmpty() },
        )

        // 只有真的拿到新数据才覆盖缓存，避免一次断网把缓存清空
        if (errors.size < 6) writeCache(cache)

        HomeLoadResult(
            cache = cache,
            errors = errors,
            empty = errors.size == 6 && previous == null,
        )
    }

    /** 继续观看：服务端播放记录按保存时间倒序。 */
    suspend fun continueWatching(): Result<List<ContinueItem>> = withContext(Dispatchers.IO) {
        safeFetch {
            api.playRecords().requireBody()
                .map { (key, record) -> ContinueItem(key, record) }
                .sortedByDescending { it.record.saveTime }
                .take(CONTINUE_LIMIT)
        }
    }

    private suspend fun writeCache(cache: HomeCache) = withContext(Dispatchers.IO) {
        val file = cacheFile() ?: return@withContext
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(HomeCache.serializer(), cache))
        }
    }

    /** 按站点分文件，换服务器不会把别站的推荐当下缓存。 */
    private fun cacheFile(): File? {
        val base = serverProvider.current()?.base ?: return null
        return File(context.cacheDir, "home/${base.host}_${base.port}.json")
    }

    private suspend fun <T> safeFetch(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun <T> Response<T>.requireBody(): T {
        if (!isSuccessful) throw IllegalStateException("HTTP ${code()}")
        return body() ?: throw IllegalStateException("服务端返回空数据")
    }

    private companion object {
        const val CONTINUE_LIMIT = 20
    }
}

/** 与 `feature.home.HomeRowIds` 保持一致的行 id。 */
private object HomeRowIds {
    const val MOVIES = "hotMovies"
    const val DUANJU = "hotDuanju"
    const val BANGUMI = "bangumiCalendar"
    const val TV = "hotTvShows"
    const val VARIETY = "hotVarietyShows"
    const val UPCOMING = "upcoming"
}
