package com.moontvplus.phone.core.common

import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/** 统一的失败原因，UI 层据此决定提示文案与是否跳登录。 */
sealed interface AppError {
    data object Network : AppError
    data class Http(val code: Int, val serverMessage: String?) : AppError
    data object Unauthorized : AppError
    data class Parse(val message: String?) : AppError
    data class Unknown(val message: String?) : AppError
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val error: AppError) : ApiResult<Nothing>
}

inline fun <T> ApiResult<T>.onOk(block: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Ok) block(value)
    return this
}

inline fun <T> ApiResult<T>.onErr(block: (AppError) -> Unit): ApiResult<T> {
    if (this is ApiResult.Err) block(error)
    return this
}

fun AppError.userMessage(): String = when (this) {
    AppError.Network -> "网络不可用，请检查网络或服务器地址"
    AppError.Unauthorized -> "登录已过期，请重新登录"
    is AppError.Http -> serverMessage?.takeIf { it.isNotBlank() }
        ?: when (code) {
            401 -> "登录已过期，请重新登录"
            403 -> "没有权限访问"
            404 -> "接口不存在"
            429 -> "请求过于频繁，请稍后再试"
            else -> "服务端错误（HTTP $code）"
        }
    is AppError.Parse -> "数据解析失败"
    is AppError.Unknown -> message ?: "未知错误"
}

/**
 * 统一包裹网络调用：把异常翻译成 [AppError]，业务层只处理 [ApiResult]。
 */
suspend fun <T> safeApiCall(json: Json, block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Ok(block())
} catch (e: HttpException) {
    val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
    val serverMessage = parseServerMessage(json, body)
    if (e.code() == 401) {
        ApiResult.Err(AppError.Unauthorized)
    } else {
        ApiResult.Err(AppError.Http(e.code(), serverMessage))
    }
} catch (e: IOException) {
    ApiResult.Err(AppError.Network)
} catch (e: SerializationException) {
    ApiResult.Err(AppError.Parse(e.message))
} catch (e: Exception) {
    ApiResult.Err(AppError.Unknown(e.message))
}

/** 服务端错误体多数是 `{"error":"..."}`，少数是纯文本（如 `Access token expired`）。 */
fun parseServerMessage(json: Json, body: String?): String? {
    val text = body?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (text.startsWith("{")) {
        runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            obj["error"]?.jsonPrimitive?.content
                ?: obj["message"]?.jsonPrimitive?.content
        }.getOrNull()?.let { return it }
    }
    return text.take(200)
}
