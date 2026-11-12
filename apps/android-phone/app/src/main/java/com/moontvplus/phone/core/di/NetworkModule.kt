package com.moontvplus.phone.core.di

import com.moontvplus.phone.BuildConfig
import com.moontvplus.phone.core.network.AuthInterceptor
import com.moontvplus.phone.core.network.MoonApi
import com.moontvplus.phone.core.network.ServerUrlInterceptor
import com.moontvplus.phone.core.network.TokenAuthenticator
import com.moontvplus.phone.core.network.UserAgentInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
        }

    /** 所有 client 共用的基础配置（地址重写 + 鉴权 + UA）。 */
    private fun baseBuilder(
        serverUrl: ServerUrlInterceptor,
        auth: AuthInterceptor,
        userAgent: UserAgentInterceptor,
    ): OkHttpClient.Builder = OkHttpClient.Builder()
        .addInterceptor(serverUrl)
        .addInterceptor(auth)
        .addInterceptor(userAgent)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)

    @Provides
    @Singleton
    @ApiClient
    fun provideApiClient(
        serverUrl: ServerUrlInterceptor,
        auth: AuthInterceptor,
        userAgent: UserAgentInterceptor,
        authenticator: TokenAuthenticator,
    ): OkHttpClient = baseBuilder(serverUrl, auth, userAgent)
        .addInterceptor(loggingInterceptor())
        .authenticator(authenticator)
        .build()

    /** 播放分片：复用鉴权，去掉请求日志（分片请求量很大）。 */
    @Provides
    @Singleton
    @MediaClient
    fun provideMediaClient(
        serverUrl: ServerUrlInterceptor,
        auth: AuthInterceptor,
        userAgent: UserAgentInterceptor,
    ): OkHttpClient = baseBuilder(serverUrl, auth, userAgent).build()

    /** 搜索 SSE：不能有读超时，否则长连接会被掐断。 */
    @Provides
    @Singleton
    @SseClient
    fun provideSseClient(
        serverUrl: ServerUrlInterceptor,
        auth: AuthInterceptor,
        userAgent: UserAgentInterceptor,
    ): OkHttpClient = baseBuilder(serverUrl, auth, userAgent)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 刷新 token 专用：独立 Dispatcher + 不带 Authenticator。
     * 与业务请求共用 Dispatcher 时，同 host 并发 401 会在认证器里互相等待。
     */
    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshClient(
        serverUrl: ServerUrlInterceptor,
        userAgent: UserAgentInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher())
        .addInterceptor(serverUrl)
        .addInterceptor(userAgent)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        @ApiClient client: OkHttpClient,
        json: Json,
    ): Retrofit = Retrofit.Builder()
        // 占位地址：真实 host 与子路径由 ServerUrlInterceptor 在请求时替换
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideMoonApi(retrofit: Retrofit): MoonApi = retrofit.create(MoonApi::class.java)

    private const val PLACEHOLDER_BASE_URL = "http://127.0.0.1/"
}
