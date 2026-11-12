package com.moontvplus.phone.data.repository

import com.moontvplus.phone.core.model.ContinueItem
import com.moontvplus.phone.core.model.Favorite
import com.moontvplus.phone.core.model.FavoriteItem
import com.moontvplus.phone.core.model.SaveFavoriteRequest
import com.moontvplus.phone.core.network.MoonApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

/** 收藏与播放记录：两个页面共用，服务端是唯一真相，本地不做离线副本。 */
class LibraryRepository(private val api: MoonApi) {

    suspend fun favorites(): Result<List<FavoriteItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = api.favorites().requireBody()
            body.map { (key, favorite) -> FavoriteItem(key, favorite) }
                .sortedByDescending { it.favorite.saveTime }
        }
    }

    suspend fun isFavorite(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { api.favorite(key).body() != null }.getOrDefault(false)
    }

    suspend fun addFavorite(key: String, favorite: Favorite): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { api.saveFavorite(SaveFavoriteRequest(key, favorite)).requireOk() }
        }

    suspend fun removeFavorite(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.deleteFavorite(key).requireOk() }
    }

    suspend fun playRecords(): Result<List<ContinueItem>> = withContext(Dispatchers.IO) {
        runCatching {
            api.playRecords().requireBody()
                .map { (key, record) -> ContinueItem(key, record) }
                .sortedByDescending { it.record.saveTime }
        }
    }

    suspend fun removePlayRecord(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.deletePlayRecord(key).requireOk() }
    }

    private fun <T> Response<T>.requireBody(): T {
        if (!isSuccessful) throw IllegalStateException("HTTP ${code()}")
        return body() ?: throw IllegalStateException("服务端返回空数据")
    }

    private fun Response<Unit>.requireOk() {
        if (!isSuccessful) throw IllegalStateException("HTTP ${code()}")
    }
}
