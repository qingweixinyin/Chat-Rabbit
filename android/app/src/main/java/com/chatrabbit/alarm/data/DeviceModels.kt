package com.chatrabbit.alarm.data

import org.json.JSONObject

/** 响铃时长可选档位（协议允许 1..120 秒） */
val RING_SECONDS_OPTIONS: List<Int> = listOf(10, 20, 30, 60)

const val DEFAULT_RING_SECONDS: Int = 20
const val MIN_RING_SECONDS: Int = 1
const val MAX_RING_SECONDS: Int = 120

/**
 * POST /api/file/delete 的成功结果。
 * 服务端返回 `{"ok":true,"deleted":"xxx.mp3","freed":1100}`。
 */
data class FileDeleteResult(
    val deletedName: String,
    /** 释放的字节数（服务端 freed 字段，缺失时为 0） */
    val freedBytes: Int,
)

/**
 * POST /api/ringtone/upload 的成功结果（路由器 v1.4.2+）。
 * 服务端返回 `{"ok":true,"file":"实际保存名","size":N}`：
 * 保留原文件名、同名自动加 `_1` 序号，并自动设为全局 `alarm_voice`。
 */
data class RingtoneUploadResult(
    /** 服务端实际保存的文件名（可能带 `_1` 之类序号） */
    val fileName: String,
    val sizeBytes: Int,
)

/** GET /api/status 的结果。 */
data class DeviceStatus(
    val deviceOnline: Boolean,
    val clients: Int,
    /** 设备当前铃声文件名（服务端 alarm_voice），可能为空表示默认铃声 */
    val alarmVoice: String?,
    /** 设备当前响铃时长（秒，服务端 ring_seconds） */
    val ringSeconds: Int,
    /** 路由器记录的最近一次玩具 IP（服务端 device_ip），可能为空或过期 */
    val deviceIp: String?,
    /** 最近一次连接时间（服务端 last_connect，通常是 "HH:mm:ss"） */
    val lastConnect: String?,
    /** 默认音乐 / 下一首文件名（服务端 music_file），可能为空 */
    val musicFile: String?,
    val raw: String,
)

/**
 * 解析 `GET /api/status` 的 JSON。
 * ApiClient 与 RouterDiscovery（自动检测 / 地址校验）共用，避免两处字段解析不一致。
 */
fun parseDeviceStatus(json: JSONObject, raw: String = json.toString()): DeviceStatus = DeviceStatus(
    deviceOnline = json.optBoolean("device_online", false),
    clients = json.optInt("clients", 0),
    alarmVoice = json.optString("alarm_voice").ifBlank { null },
    ringSeconds = json.optInt("ring_seconds", DEFAULT_RING_SECONDS)
        .coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS),
    deviceIp = json.optString("device_ip").ifBlank { null },
    lastConnect = json.optString("last_connect").ifBlank { null },
    musicFile = json.optString("music_file").ifBlank { null },
    raw = raw,
)

/**
 * GET /api/devices 中的一台设备（服务端 v1.5.0 多设备支持）。
 */
data class DeviceInfo(
    val id: String,
    val name: String,
    val online: Boolean,
    val ip: String? = null,
    val lastConnect: String? = null,
    val alarmCount: Int = 0,
    val alarmVoice: String? = null,
    val musicFile: String? = null,
)

/**
 * GET /api/devices 的结果：
 * `{"ok":true,"default":"AI91144031DD06A2","devices":[{...}]}`
 *
 * [defaultDeviceId] 是服务端的“默认设备”；App 未选择时回落到它。
 */
data class DeviceList(
    val defaultDeviceId: String?,
    val devices: List<DeviceInfo>,
)

/**
 * GET /api/dev/list 中的一条设备侧闹钟。
 * 字段含义（设备协议）：
 * - na = 名称, th = 时, tm = 分, sd = 起始 unix 秒,
 * - wk = 星期掩码（bit0 = 周一；63 = 每天；仅一次 = 该日期的单个星期位）,
 * - s  = 开关（1 = 开）,
 * - rv = 本条独立铃声（空 = 跟随全局 alarm_voice）,
 * - once = 服务端标记的“仅一次”（可选，v1.4.8+ 会返回）
 *
 * [index] 是它在服务端列表里的下标（0 开始），`POST /api/dev/del {"index":n}` 用它定位。
 */
data class DeviceAlarm(
    val index: Int,
    val name: String,
    val hour: Int,
    val minute: Int,
    val startUnixSeconds: Long,
    val weekdayMask: Int,
    val enabled: Boolean,
    val ringFile: String? = null,
    /** 服务端明确标记“仅一次”（`once:true`）；旧服务端可能不返回 */
    val once: Boolean = false,
)

/** 位掩码 → 星期集合（1 = 周一 … 7 = 周日） */
fun daysFromMask(mask: Int): Set<Int> =
    (0..6).filter { (mask and (1 shl it)) != 0 }.map { it + 1 }.toSet()

/** 把 `sd`（unix 秒）转成本地日期；非法/<=0 返回 null。 */
fun DeviceAlarm.localDateOrNull(): java.time.LocalDate? {
    if (startUnixSeconds <= 0) return null
    return runCatching {
        java.time.Instant.ofEpochSecond(startUnixSeconds)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
    }.getOrNull()
}

/**
 * 设备表条目 → 本地展示用的闹钟。
 *
 * 本地镜像**完全由路由器表生成**（路由器是唯一真源），只用于展示/编辑。
 * id 由「下标 + 内容指纹」派生：每次 `refreshFromDevice()` 重新生成，保证列表 key 唯一。
 *
 * 仅一次的判定顺序：`once == true`（服务端标记）→ `wk == 0`（旧服务端）→ 否则按星期掩码。
 */
fun DeviceAlarm.toLocalAlarm(): Alarm {
    val date = localDateOrNull()
    val repeat = when {
        // 服务端明确标记仅一次
        once && date != null -> RepeatMode.Once(date)
        // 旧服务端用 wk=0 表示“不重复”
        weekdayMask <= 0 -> {
            if (date != null) RepeatMode.Once(date) else RepeatMode.Daily
        }
        // 服务端约定 63 = 每天
        weekdayMask == WEEK_MASK_EVERY_DAY -> RepeatMode.Daily
        else -> {
            val days = daysFromMask(weekdayMask)
            if (days.isEmpty() || days.size == 7) RepeatMode.Daily else RepeatMode.Weekly(days)
        }
    }
    val fingerprint = "$name|$hour|$minute|$weekdayMask|$startUnixSeconds|$ringFile|$once"
    return Alarm(
        id = "dev:$index:${fingerprint.hashCode()}",
        hour = hour,
        minute = minute,
        label = name,
        enabled = enabled,
        repeat = repeat,
        // 旧字段，仅兼容旧数据；铃声现在是每条闹钟的 ringFile（服务端 rv/ring）
        ringtoneUri = null,
        createdAt = startUnixSeconds * 1000L,
        ringFile = ringFile?.takeIf { it.isNotBlank() },
    )
}
