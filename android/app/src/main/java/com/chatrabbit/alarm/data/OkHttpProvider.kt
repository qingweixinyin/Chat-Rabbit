package com.chatrabbit.alarm.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局共享的 OkHttpClient。
 * 路由器是家庭内网设备，连接超时给短一点；铃声上传可能较大，写超时给长一点。
 */
object OkHttpProvider {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 探测 / 地址校验专用客户端：
     * - 连接超时 1.5s（自动检测时每个候选只有 1.5s 预算）
     * - 单次请求总超时由 `Call.timeout()` 按调用覆盖（检测 1.5s / 校验 5s）
     */
    val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(6000, TimeUnit.MILLISECONDS)
            .writeTimeout(6000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
