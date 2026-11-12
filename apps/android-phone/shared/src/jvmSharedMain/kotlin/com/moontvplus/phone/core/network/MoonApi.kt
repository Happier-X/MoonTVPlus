package com.moontvplus.phone.core.network

import com.moontvplus.phone.core.model.BangumiCalendarDay
import com.moontvplus.phone.core.model.CodedListResponse
import com.moontvplus.phone.core.model.DoubanCategoriesResponse
import com.moontvplus.phone.core.model.LoginRequest
import com.moontvplus.phone.core.model.LoginResponse
import com.moontvplus.phone.core.model.PlayRecord
import com.moontvplus.phone.core.model.SavePlayRecordRequest
import com.moontvplus.phone.core.model.Favorite
import com.moontvplus.phone.core.model.SaveFavoriteRequest
import com.moontvplus.phone.core.model.SearchResult
import com.moontvplus.phone.core.model.ServerConfig
import com.moontvplus.phone.core.model.TmdbItem
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 站点的 HTTP 接口。
 *
 * 路径都写相对形式：真正的 host 与子路径前缀由 [ServerUrlInterceptor] 在请求时补上，
 * 所以切换服务器不需要重建 Retrofit。
 */
interface MoonApi {

    @POST("api/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("api/logout")
    suspend fun logout(): Response<Unit>

    /** 探测服务器地址时会临时构造一个指向候选地址的调用，见 ServerRepository。 */
    @GET("api/server-config")
    suspend fun serverConfig(): ServerConfig

    // ---- 首页 ----

    @GET("api/douban/categories")
    suspend fun doubanCategories(
        @Query("kind") kind: String,
        @Query("category") category: String,
        @Query("type") type: String,
        @Query("limit") limit: Int = 20,
        @Query("start") start: Int = 0,
    ): Response<DoubanCategoriesResponse>

    @GET("api/bangumi/calendar")
    suspend fun bangumiCalendar(): Response<List<BangumiCalendarDay>>

    @GET("api/duanju/recommends")
    suspend fun duanjuRecommends(): Response<CodedListResponse<SearchResult>>

    @GET("api/tmdb/upcoming")
    suspend fun tmdbUpcoming(): Response<CodedListResponse<TmdbItem>>

    // ---- 播放记录 ----

    @GET("api/playrecords")
    suspend fun playRecords(): Response<Map<String, PlayRecord>>

    @POST("api/playrecords")
    suspend fun savePlayRecord(@Body body: SavePlayRecordRequest): Response<Unit>

    // ---- 搜索 ----

    /** 普通搜索：所有源聚合成一次响应，用作 SSE 断流时的回退。 */
    @GET("api/search")
    suspend fun search(@Query("q") keyword: String): Response<SearchResponse>

    /** 流式搜索：SSE，事件体只有 `data: {...}`。 */
    @GET("api/search/ws")
    fun searchStream(@Query("q") keyword: String): Call<ResponseBody>

    @GET("api/searchhistory")
    suspend fun searchHistory(): Response<List<String>>

    @POST("api/searchhistory")
    suspend fun addSearchHistory(@Body body: SearchHistoryRequest): Response<Unit>

    @DELETE("api/searchhistory")
    suspend fun removeSearchHistory(@Query("keyword") keyword: String): Response<Unit>

    // ---- 详情 ----

    /** 返回结构与搜索结果一致，episodes 里就是可播放地址。 */
    @GET("api/source-detail")
    suspend fun sourceDetail(
        @Query("source") source: String,
        @Query("id") videoId: String,
    ): Response<SearchResult>

    // ---- 收藏 ----

    /** 不带 key 返回全部收藏；带 key 返回单条（可能 body 为 null）。 */
    @GET("api/favorites")
    suspend fun favorites(): Response<Map<String, Favorite>>

    @GET("api/favorites")
    suspend fun favorite(@Query("key") key: String): Response<Favorite>

    @POST("api/favorites")
    suspend fun saveFavorite(@Body body: SaveFavoriteRequest): Response<Unit>

    @DELETE("api/favorites")
    suspend fun deleteFavorite(@Query("key") key: String): Response<Unit>

    // ---- 播放记录 ----

    @DELETE("api/playrecords")
    suspend fun deletePlayRecord(@Query("key") key: String): Response<Unit>
}

@Serializable
data class SearchResponse(val results: List<SearchResult> = emptyList())

@Serializable
data class SearchHistoryRequest(val keyword: String)

