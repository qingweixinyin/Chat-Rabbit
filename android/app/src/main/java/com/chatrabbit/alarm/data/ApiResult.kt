package com.chatrabbit.alarm.data

/**
 * 统一的网络调用结果封装，避免把异常（IOException / JSONException）泄漏到 UI 层。
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val data: T) : ApiResult<T>
    data class Err(val message: String) : ApiResult<Nothing>
}

/** 便于 UI 层做 “成功/失败” 分支，无需写 when 全量分支。 */
val ApiResult<*>.isFailure: Boolean
    get() = this is ApiResult.Err

/** 取错误信息（成功时返回 null）。 */
fun ApiResult<*>.errorMessage(): String? = (this as? ApiResult.Err)?.message
