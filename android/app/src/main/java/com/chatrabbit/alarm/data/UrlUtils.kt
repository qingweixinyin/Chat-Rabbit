package com.chatrabbit.alarm.data

import java.util.Locale

/**
 * 路由器 / 玩具设备 API 端口（自建 mock 云端）。
 *
 * 2026-10 起服务端默认端口改为 **8890**：8898 被 kongke 插件的劫持面板占用，
 * 两者同时监听会冲突。服务端 v1.6.0 会同时监听 8890（必须）与 8898（尽力），
 * App 优先用 8890，探测时再回落 8898 兼容旧服务端。
 */
const val DEFAULT_ROUTER_PORT: Int = 8890

/** 旧端口（服务端 < v1.6.0；在装了 kongke 的路由器上可能被占用）。仅用于兼容探测与提示。 */
const val LEGACY_ROUTER_PORT: Int = 8898

/** 默认路由器地址（可在「设备 → 修改地址」或「设置」里修改） */
const val DEFAULT_ROUTER_BASE_URL: String = "http://192.168.6.1:$DEFAULT_ROUTER_PORT"

/**
 * 规范化用户输入的路由器地址，支持这些写法：
 * - `192.168.3.1`              → `http://192.168.3.1:8890`
 * - `192.168.3.1:9000`         → `http://192.168.3.1:9000`
 * - `http://192.168.3.1`       → `http://192.168.3.1:8890`
 * - `http://192.168.3.1:8890/` → `http://192.168.3.1:8890`
 * - 空输入                     → 默认地址
 *
 * 规则：缺协议补 `http://`；只有主机名（没有端口）时补 `:8890`；去掉结尾多余的 `/`。
 */
fun normalizeBaseUrl(raw: String): String {
    var value = raw.trim()
    if (value.isEmpty()) return DEFAULT_ROUTER_BASE_URL

    if (!value.startsWith("http://", ignoreCase = true) &&
        !value.startsWith("https://", ignoreCase = true)
    ) {
        value = "http://$value"
    }

    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return DEFAULT_ROUTER_BASE_URL

    val scheme = value.substring(0, schemeEnd).lowercase(Locale.US)
    var rest = value.substring(schemeEnd + 3)
    while (rest.endsWith("/")) {
        rest = rest.dropLast(1)
    }

    val slashIndex = rest.indexOf('/')
    val authority = if (slashIndex >= 0) rest.substring(0, slashIndex) else rest
    val path = if (slashIndex >= 0) rest.substring(slashIndex) else ""
    if (authority.isBlank()) return DEFAULT_ROUTER_BASE_URL

    val hasPort = authority.contains(':')
    val normalizedAuthority = if (hasPort) authority else "$authority:$DEFAULT_ROUTER_PORT"
    return "$scheme://$normalizedAuthority$path"
}

/** 取 baseUrl 里的端口（缺失时按默认端口 8890 处理）。 */
fun portOf(baseUrl: String): Int {
    val normalized = normalizeBaseUrl(baseUrl)
    val authority = normalized.substringAfter("://").substringBefore("/")
    return authority.substringAfterLast(":", "").toIntOrNull() ?: DEFAULT_ROUTER_PORT
}

/** 是否还在用旧端口 8898（设置页据此提示“建议升级服务端到 v1.6.0”）。 */
fun isLegacyPort(baseUrl: String): Boolean = portOf(baseUrl) == LEGACY_ROUTER_PORT
