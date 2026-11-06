package com.chatrabbit.alarm.ble

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * BLE 配网需要的运行时权限：
 * - Android 12+：BLUETOOTH_SCAN（neverForLocation）+ BLUETOOTH_CONNECT
 * - Android 11 及以下：ACCESS_FINE_LOCATION（系统用定位权限管控 BLE 扫描）
 */
object BlePermissions {

    fun required(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun granted(context: Context): Boolean = required().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** 权限被“拒绝且不再询问”后，只能去应用详情页手动打开。 */
    fun openAppPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
