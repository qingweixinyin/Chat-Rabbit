package com.chatrabbit.alarm.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.chatrabbit.alarm.data.Alarm

/** 列表/编辑页显示用的闹钟名称 */
fun Alarm.displayLabel(): String = label.ifBlank { "闹钟" }

/** 把铃声 URI 转成可读名字（取路径最后一段；系统默认回落到文字说明） */
fun ringtoneDisplayName(uriString: String?): String {
    if (uriString.isNullOrBlank()) return "默认闹钟铃声"
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return "自定义铃声"
    val segment = uri.lastPathSegment?.substringAfterLast('/')
    return if (segment.isNullOrBlank()) "自定义铃声" else segment
}

/** 跳系统“应用详情”，各权限设置页的首选兜底入口 */
fun Context.openAppDetailsSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
}

/** 通知权限设置（Android 13 拒绝过一次后必须去系统设置里开） */
fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }.onFailure { openAppDetailsSettings() }
}
