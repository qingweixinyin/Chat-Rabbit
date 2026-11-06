package com.chatrabbit.alarm.ble

import android.content.Context
import android.net.wifi.WifiManager

/**
 * 尽力读取当前 WiFi SSID，用于配网页预填。
 *
 * 说明：Android 8+ 读取 SSID 需要定位权限；Android 12+ 我们用的是
 * `BLUETOOTH_SCAN(neverForLocation)`，没有定位权限时系统会返回 `<unknown ssid>`，
 * 此时返回 null，由用户手动输入（不影响配网功能）。
 */
@Suppress("DEPRECATION")
fun currentWifiSsid(context: Context): String? = runCatching {
    val manager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return@runCatching null
    val rawSsid = manager.connectionInfo?.ssid ?: return@runCatching null
    val cleaned = rawSsid.trim().removeSurrounding("\"")
    when {
        cleaned.isBlank() -> null
        cleaned.contains("unknown", ignoreCase = true) -> null
        else -> cleaned
    }
}.getOrNull()
