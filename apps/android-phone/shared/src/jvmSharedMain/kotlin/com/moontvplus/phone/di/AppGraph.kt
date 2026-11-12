package com.moontvplus.phone.di

import com.moontvplus.phone.core.network.ApiUrlResolver
import com.moontvplus.phone.core.network.AuthInterceptor
import com.moontvplus.phone.core.network.ImageUrlBuilder
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.ServerProvider
import com.moontvplus.phone.core.network.ServerUrlInterceptor
import com.moontvplus.phone.core.network.SessionManager
import com.moontvplus.phone.core.network.TokenAuthenticator
import com.moontvplus.phone.core.network.UserAgentInterceptor
import com.moontvplus.phone.core.platform.Platform
import com.moontvplus.phone.core.platform.PlatformServices
import com.moontvplus.phone.core.storage.SessionStore
import com.moontvplus.phone.core.storage.SettingsStore
import com.moontvplus.phone.core.storage.TextFileStore
import com.moontvplus.phone.data.repository.AuthRepository
import com.moontvplus.phone.data.repository.HomeRepository
import com.moontvplus.phone.data.repository.SearchRepository
import com.moontvplus.phone.data.repository.ServerRepository
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 手写依赖装配。
 *
 * 为什么不用 Hilt：Hilt 只能在 Android 上跑，而这个模块要同时给 Android 和桌面用。
 * 依赖图很小（十来个单例），手写反而更直观、也省掉了 KSP 的构建时间。
 */
class AppGraph(
    val platformServices: PlatformServices = PlatformServices(),
    val json: Json = defaultJson(),
) {

    val textStore = TextFileStore()
    val settingsStore = SettingsStore(textStore, json)
    val sessionStore = SessionStore(textStore, json)
    val serverProvider = ServerProvider()
    val urlResolver = ApiUrlResolver(serverProvider)

    private val serverUrlInterceptor = ServerUrlInterceptor(urlResolver)
    private val userAgentInterceptor = UserAgentInterceptor()

    /** 刷新专用 client：独立 Dispatcher，不带 Authenticator，避免并发 401 互相等待。 */
    private val refreshClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher())
        .addInterceptor(serverUrlInterceptor)
        .addInterceptor(userAgentInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val sessionManager = SessionManager(sessionStore, urlResolver, json, refreshClient)

    private val authInterceptor = AuthInterceptor(sessionManager, urlResolver)
    private val authenticator = TokenAuthenticator(sessionManager, urlResolver)

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .addInterceptor(serverUrlInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(userAgentInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (Platform.debug) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        redactHeader("Authorization")
    }

    /** 业务接口 */
    val apiClient: OkHttpClient = baseBuilder()
        .addInterceptor(loggingInterceptor)
        .authenticator(authenticator)
        .build()

    /** 播放分片：复用鉴权，不记日志 */
    val mediaClient: OkHttpClient = baseBuilder().build()

    /** 搜索 SSE：不能有读超时 */
    val sseClient: OkHttpClient = baseBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        // 占位地址：真实 host 与子路径由 ServerUrlInterceptor 在请求时替换
        .baseUrl("http://127.0.0.1/")
        .client(apiClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val moonApi: MoonApi = retrofit.create(MoonApi::class.java)

    val imageUrlBuilder = ImageUrlBuilder(urlResolver)

    val serverRepository = ServerRepository(settingsStore, serverProvider, json)
    val authRepository = AuthRepository(
        api = moonApi,
        sessions = sessionManager,
        settings = settingsStore,
        serverProvider = serverProvider,
        cookies = platformServices.cookies,
        json = json,
    )
    val homeRepository = HomeRepository(moonApi, json, serverProvider, textStore)
    val searchRepository = SearchRepository(moonApi, json, urlResolver, sseClient)

    /** 启动时把设置读进内存；各平台在创建界面之前调一次。 */
    suspend fun initialize() {
        settingsStore.load()
    }

    companion object {
        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = false
        }
    }
}
