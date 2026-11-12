package com.moontvplus.phone.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局唯一的当前服务器地址。
 *
 * 拦截器每次请求都读它，所以切换服务器不需要重建 Retrofit / OkHttpClient。
 */
@Singleton
class ServerProvider @Inject constructor() {

    private val _address = MutableStateFlow<ServerAddress?>(null)
    val address: StateFlow<ServerAddress?> = _address.asStateFlow()

    fun set(address: ServerAddress?) {
        _address.value = address
    }

    fun current(): ServerAddress? = _address.value
}
