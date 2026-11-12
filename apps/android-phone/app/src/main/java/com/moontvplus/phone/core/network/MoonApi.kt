package com.moontvplus.phone.core.network

import com.moontvplus.phone.core.model.LoginRequest
import com.moontvplus.phone.core.model.LoginResponse
import com.moontvplus.phone.core.model.ServerConfig
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

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
}
