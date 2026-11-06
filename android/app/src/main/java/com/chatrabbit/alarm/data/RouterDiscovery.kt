package com.chatrabbit.alarm.data

import com.chatrabbit.alarm.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 自动检测时依次探测的候选地址（**兜底列表**）。
 *
 * 真正优先的是 [WifiRouteCandidates.build]：按手机当前 Wi-Fi 的网关/网段生成。
 * 这里只保留常见家用网段，供"没连 Wi-Fi / 取不到网关"时使用。
 */
object RouterCandidates {

    private val COMMON_HOSTS: List<String> = listOf(
        "192.168.1.1",
        "192.168.3.1",
        "192.168.6.1",
        "192.168.31.1",
        "192.168.0.1",
    )

    val DEFAULT_LIST: List<String> = COMMON_HOSTS.flatMap { host ->
        listOf(
            "http://$host:$DEFAULT_ROUTER_PORT",
            "http://$host:$LEGACY_ROUTER_PORT",
        )
    }
}

/** 单次探测结果 */
sealed interface ProbeOutcome {
    data class Success(val baseUrl: String, val status: DeviceStatus) : ProbeOutcome
    data class Failure(val baseUrl: String, val reason: String) : ProbeOutcome
}

/**
 * 路由器地址探测 / 校验。
 *
 * 判定“这个地址是聊天兔服务”的标准（与需求一致）：
 * `GET {base}/api/status` 返回 2xx、是合法 JSON，且 **含 `ok` 或 `device_online` 字段**。
 */
class RouterDiscovery(
    private val context: android.content.Context,
    private val settingsRepository: SettingsRepository,
    private val client: OkHttpClient = OkHttpProvider.probeClient,
) {

    /** 探测单个地址。 */
    suspend fun probe(
        rawBaseUrl: String,
        timeoutMillis: Long = PROBE_TIMEOUT_MS,
    ): ProbeOutcome = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(rawBaseUrl)
        val request = Request.Builder()
            .url("$baseUrl/api/status")
            .get()
            .build()

        try {
            val call = client.newCall(request)
            call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ProbeOutcome.Failure(
                        baseUrl,
                        httpErrorText(response.code, body),
                    )
                }
                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return@withContext ProbeOutcome.Failure(
                        baseUrl,
                        "响应不是 JSON（该地址可能不是聊天兔服务）",
                    )
                if (!json.has("ok") && !json.has("device_online")) {
                    return@withContext ProbeOutcome.Failure(
                        baseUrl,
                        "响应缺少 ok / device_online 字段（不是聊天兔服务）",
                    )
                }
                ProbeOutcome.Success(baseUrl, parseDeviceStatus(json, body))
            }
        } catch (e: SocketTimeoutException) {
            ProbeOutcome.Failure(baseUrl, "连接超时（${timeoutMillis}ms 内无响应）")
        } catch (e: IOException) {
            ProbeOutcome.Failure(baseUrl, "无法连接：${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            ProbeOutcome.Failure(baseUrl, "探测异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 依次探测候选地址，第一个成功的会自动写入设置并返回。
     * @param onProgress 回调 (当前序号从 0 开始, 总数, 正在探测的地址)
     */
    suspend fun detect(
        candidates: List<String> = WifiRouteCandidates.build(context),
        timeoutMillis: Long = PROBE_TIMEOUT_MS,
        onProgress: (index: Int, total: Int, baseUrl: String) -> Unit = { _, _, _ -> },
    ): ProbeOutcome.Success? = withContext(Dispatchers.IO) {
        candidates.forEachIndexed { index, candidate ->
            onProgress(index, candidates.size, candidate)
            when (val outcome = probe(candidate, timeoutMillis)) {
                is ProbeOutcome.Success -> {
                    settingsRepository.setBaseUrl(outcome.baseUrl)
                    return@withContext outcome
                }

                is ProbeOutcome.Failure -> Unit
            }
        }
        null
    }

    /**
     * 启动时在后台跑一次自动检测（用 DataStore 标记只跑一次）。
     * 只有当前地址仍是默认值时才覆盖，避免把用户自定义过的地址冲掉。
     *
     * 额外：**旧端口 8898 自动迁移** —— 若当前地址还指向 8898，
     * 先探测同一主机的 8890；通就切过去。这样装在装了 kongke 的路由器上
     * （8898 被劫持面板占用）的旧用户，升级 App 后能自动自救，无需手动改地址。
     */
    suspend fun autoConfigureOnce(): ProbeOutcome.Success? {
        val current = settingsRepository.baseUrlOnce()

        if (isLegacyPort(current)) {
            val host = current.substringAfter("://").substringBefore(":").substringBefore("/")
            if (host.isNotBlank()) {
                val migrated = probe("http://$host:$DEFAULT_ROUTER_PORT", PROBE_TIMEOUT_MS)
                if (migrated is ProbeOutcome.Success) {
                    settingsRepository.setBaseUrl(migrated.baseUrl)
                    settingsRepository.markAutoDetectDone()
                    return migrated
                }
            }
        }

        if (settingsRepository.isAutoDetectDoneOnce()) return null
        val isDefault = current.trimEnd('/') == SettingsRepository.DEFAULT_BASE_URL.trimEnd('/')
        val result = if (isDefault) detect() else null
        settingsRepository.markAutoDetectDone()
        return result
    }

    private fun httpErrorText(code: Int, body: String): String = when (code) {
        404 -> "HTTP 404：该地址没有 /api/status（IP 或端口不对）"
        401, 403 -> "HTTP $code：服务拒绝访问"
        in 500..599 -> "HTTP $code：路由器服务内部错误"
        else -> "HTTP $code" + if (body.isBlank()) "" else "：${body.take(120)}"
    }

    companion object {
        /** 自动检测时每个候选的超时 */
        const val PROBE_TIMEOUT_MS: Long = 1500L

        /** 手动保存前校验的超时（比检测宽松一些） */
        const val VALIDATE_TIMEOUT_MS: Long = 5000L
    }
}
