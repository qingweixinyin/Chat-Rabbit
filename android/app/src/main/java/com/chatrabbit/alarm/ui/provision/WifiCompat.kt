package com.chatrabbit.alarm.ui.provision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.delay

/**
 * WiFi 兼容性自检（**只读**：只调用 startScan + 读 scanResults，不修改任何网络配置）。
 *
 * 目的：配网前先看清目标 SSID 的 2.4G/信道/加密/带宽，
 * 因为玩具（AC791N）只支持 2.4GHz + WPA2-PSK(AES) + 信道 1–11 + 20/40MHz。
 * 典型踩坑：SSID 在 5G、信道 12/13、WPA3/SAE、TKIP —— 都会表现成
 * 玩具语音"请检查 SSID 名称和密码"。
 */
data class WifiCompatReport(
    val found: Boolean,
    val exactSsid: String?,
    val similarSsids: List<String>,
    val channel: Int?,
    val bandLabel: String,
    val securityLabel: String,
    val widthMhz: Int?,
    val issues: List<String>,
) {
    val ok: Boolean get() = found && issues.isEmpty()
}

private const val FREQ_2G_MIN = 2400
private const val FREQ_2G_MAX = 2500
private const val FREQ_5G_MIN = 4900
private const val FREQ_5G_MAX = 5900

fun wifiScanPermissionGranted(context: Context): Boolean {
    val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!fine) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // 13+ 扫 SSID 需要 NEARBY_WIFI_DEVICES（或定位权限，二者满足其一）
        val nearby = context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
        return nearby || fine
    }
    return true
}

private fun channelOf(freqMhz: Int): Int? = when {
    freqMhz == 2484 -> 14
    freqMhz in FREQ_2G_MIN..FREQ_2G_MAX -> (freqMhz - 2407) / 5
    else -> null
}

private fun securityOf(capabilities: String): String {
    val caps = capabilities.uppercase()
    return when {
        caps.contains("WPA3") || caps.contains("SAE") -> "WPA3 / SAE"
        caps.contains("WPA2") && caps.contains("TKIP") -> "WPA2 + TKIP"
        caps.contains("WPA2") -> "WPA2 (AES/CCMP)"
        caps.contains("WPA") -> "WPA (旧)"
        caps.contains("WEP") -> "WEP"
        else -> "开放网络"
    }
}

/** 扫描一次并评估目标 SSID（约 2.5 秒）。 */
suspend fun checkWifiCompat(context: Context, targetSsid: String): WifiCompatReport {
    val app = context.applicationContext
    val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    if (wm == null) {
        return WifiCompatReport(false, null, emptyList(), null, "未知", "未知", null,
            listOf("此设备没有 WiFi 管理服务"))
    }
    runCatching { @Suppress("DEPRECATION") wm.startScan() }
    delay(2500)
    val results: List<ScanResult> = runCatching {
        @Suppress("DEPRECATION")
        wm.scanResults ?: emptyList()
    }.getOrDefault(emptyList())

    val exact = results.firstOrNull { it.SSID == targetSsid }
    if (exact == null) {
        // 找出"看起来像"的（大小写不同 / 首尾空格不同 / 包含关系）
        val similar = results.map { it.SSID }
            .filter { it.isNotBlank() && it != targetSsid }
            .filter {
                it.trim().equals(targetSsid.trim(), ignoreCase = true) ||
                    it.contains(targetSsid.trim(), ignoreCase = true) ||
                    targetSsid.trim().contains(it.trim(), ignoreCase = true)
            }
            .distinct()
            .take(5)
        val issues = mutableListOf("手机扫不到 SSID「$targetSsid」")
        if (similar.isNotEmpty()) {
            issues += "附近有相近的 SSID：${similar.joinToString(" / ") { "「$it」" }}（注意大小写与首尾空格）"
        } else {
            issues += "该 SSID 可能不是 2.4G、被隐藏，或 2.4G 无线没开"
        }
        return WifiCompatReport(false, null, similar, null, "-", "-", null, issues)
    }

    val channel = channelOf(exact.frequency)
    val band = when {
        exact.frequency in FREQ_2G_MIN..FREQ_2G_MAX -> "2.4G"
        exact.frequency in FREQ_5G_MIN..FREQ_5G_MAX -> "5G"
        else -> "${exact.frequency}MHz"
    }
    val security = securityOf(exact.capabilities)
    @Suppress("DEPRECATION")
    val width = runCatching { exact.channelWidth }.getOrNull()
    val caps = exact.capabilities.uppercase()

    val issues = mutableListOf<String>()
    if (band != "2.4G") {
        issues += "这个 SSID 在 $band（${exact.frequency}MHz），玩具只支持 2.4G"
    } else {
        if (channel != null && channel >= 12) {
            issues += "信道 $channel：国产 IoT 芯片通常只支持 1–11，会扫不到或关联失败 → 请把 2.4G 信道固定为 6"
        }
        if (caps.contains("TKIP")) {
            issues += "加密含 TKIP：玩具只支持 AES → 请把加密算法改成 AES"
        }
        if (caps.contains("WPA3") || caps.contains("SAE")) {
            issues += "该网络启用了 WPA3/SAE：玩具只支持 WPA2-PSK → 请关掉 WPA3/混合模式"
        }
        if (caps.contains("MFP-REQUIRED") || caps.contains("MFP REQUIRED")) {
            issues += "强制了受保护管理帧(PMF/802.11w)：老设备会关联失败 → 请关闭"
        }
        if (width != null && width >= 80) {
            issues += "2.4G 带宽 ${width}MHz 偏宽，建议固定 20MHz 更稳"
        }
    }
    return WifiCompatReport(true, exact.SSID, emptyList(), channel, band, security, width, issues)
}
