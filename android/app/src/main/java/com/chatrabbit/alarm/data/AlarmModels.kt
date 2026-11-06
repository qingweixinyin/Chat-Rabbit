package com.chatrabbit.alarm.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * 重复规则：
 * - [Daily]   每天
 * - [Weekly]  指定星期（1 = 周一 … 7 = 周日，与 java.time.DayOfWeek.value 一致）
 * - [Once]    仅一次（指定日期）
 */
sealed interface RepeatMode {
    data object Daily : RepeatMode
    data class Weekly(val days: Set<Int>) : RepeatMode
    data class Once(val date: LocalDate) : RepeatMode
}

/** 一个本地闹钟（路由器的镜像）。 */
data class Alarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val label: String,
    val enabled: Boolean,
    val repeat: RepeatMode,
    /** 旧版本遗留：本地铃声 URI，仅用于兼容旧数据（v1.7.0 起不再使用，恒为 null） */
    val ringtoneUri: String?,
    val createdAt: Long,
    /**
     * 这条闹钟**独立**的铃声文件名（服务端 `rv`/`ring`）。
     * null 或空串 = 跟随全局默认铃声（`/api/status.alarm_voice`）。
     */
    val ringFile: String? = null,
)

/** 星期位掩码：bit0 = 周一 … bit6 = 周日（服务端 `wk` 字段） */
const val WEEK_MASK_EVERY_DAY: Int = 63

/** 7 位全选（周一~周日） */
const val WEEK_MASK_ALL_DAYS: Int = 0b111_1111

/**
 * 把「星期几集合」转成设备/服务端的位掩码。
 * 1 = 周一 → bit0，7 = 周日 → bit6。
 */
fun weekMaskOf(days: Set<Int>): Int =
    days.fold(0) { acc, day -> acc or (1 shl (day - 1).coerceIn(0, 6)) }

/**
 * 同步到路由器时的一条 add 载荷。
 *
 * 路由器 v1.2.5 起支持 `wk` 位掩码：**一条闹钟就是一条设备记录**，
 * 不再需要把「周一~周五」拆成 5 条。仅一次闹钟仍用 `onceDate`。
 */
data class DeviceAlarmPayload(
    /**
     * 星期掩码（bit0=周一…bit6=周日）。
     * 仅一次闹钟 = 该日期对应的**单个星期位**（固件把 wk 当星期选择掩码，wk=0 表示一天都没选、永不触发）。
     */
    val weekMask: Int?,
    /** "yyyy-MM-dd"，非空表示仅一次（服务端用它做“响完自动删”的定时） */
    val onceDate: String?,
    /** 这条闹钟独立的铃声文件名（`ring`）；null = 跟随全局默认 */
    val ringFile: String?,
)

/**
 * 把一个本地闹钟转换成设备侧的单条载荷：
 * - 每天      -> wk = 63（服务端约定的“每天”）
 * - 指定星期  -> wk = 位掩码（周一~周五 = 31，周一~周日 = 127）
 * - 仅一次    -> `onceDate` + **该日期对应的单个星期位**（例如 2026-10-29 周四 → wk = 8）
 * - 铃声      -> 带上 `ring`（空 = 跟随全局 `/api/status.alarm_voice`）
 *
 * > 固件把 `wk` 当“星期选择位掩码”，`wk=0` = 一天都没选 → 永不触发，
 * > 所以仅一次**不能**发 `wk:0`，必须发该日期的星期位。
 */
fun Alarm.toDeviceAlarmPayload(): DeviceAlarmPayload = when (val mode = repeat) {
    RepeatMode.Daily -> DeviceAlarmPayload(
        weekMask = WEEK_MASK_EVERY_DAY,
        onceDate = null,
        ringFile = ringFile?.takeIf { it.isNotBlank() },
    )

    is RepeatMode.Weekly -> {
        val mask = weekMaskOf(mode.days)
        DeviceAlarmPayload(
            weekMask = if (mask == 0) WEEK_MASK_EVERY_DAY else mask,
            onceDate = null,
            ringFile = ringFile?.takeIf { it.isNotBlank() },
        )
    }

    is RepeatMode.Once -> DeviceAlarmPayload(
        // DayOfWeek: 1=周一 … 7=周日 → 位移正好对应 bit0=周一
        weekMask = 1 shl (mode.date.dayOfWeek.value - 1),
        onceDate = mode.date.toString(),
        ringFile = ringFile?.takeIf { it.isNotBlank() },
    )
}

/**
 * DataStore 里用 JSON 字符串保存闹钟列表（避免引入 Room）。
 * 手写 org.json 编解码，字段向前兼容：缺失字段一律有默认值。
 */
object AlarmJson {

    private const val KEY_ID = "id"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_LABEL = "label"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RINGTONE = "ringtoneUri"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_REPEAT_TYPE = "repeatType"
    private const val KEY_WEEKDAYS = "weekdays"
    private const val KEY_ONCE_DATE = "onceDate"
    private const val KEY_RING_FILE = "ringFile"

    private const val TYPE_DAILY = "daily"
    private const val TYPE_WEEKLY = "weekly"
    private const val TYPE_ONCE = "once"

    fun encode(alarms: List<Alarm>): String {
        val array = JSONArray()
        for (alarm in alarms) {
            val item = JSONObject()
            item.put(KEY_ID, alarm.id)
            item.put(KEY_HOUR, alarm.hour)
            item.put(KEY_MINUTE, alarm.minute)
            item.put(KEY_LABEL, alarm.label)
            item.put(KEY_ENABLED, alarm.enabled)
            item.put(KEY_RINGTONE, alarm.ringtoneUri ?: JSONObject.NULL)
            item.put(KEY_RING_FILE, alarm.ringFile ?: JSONObject.NULL)
            item.put(KEY_CREATED_AT, alarm.createdAt)
            when (val mode = alarm.repeat) {
                RepeatMode.Daily -> item.put(KEY_REPEAT_TYPE, TYPE_DAILY)
                is RepeatMode.Weekly -> {
                    item.put(KEY_REPEAT_TYPE, TYPE_WEEKLY)
                    item.put(KEY_WEEKDAYS, JSONArray(mode.days.sorted()))
                }
                is RepeatMode.Once -> {
                    item.put(KEY_REPEAT_TYPE, TYPE_ONCE)
                    item.put(KEY_ONCE_DATE, mode.date.toString())
                }
            }
            array.put(item)
        }
        return array.toString()
    }

    fun decode(raw: String?): List<Alarm> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = ArrayList<Alarm>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            result.add(decodeItem(item))
        }
        return result
    }

    private fun decodeItem(item: JSONObject): Alarm {
        val id = item.optString(KEY_ID).ifBlank { UUID.randomUUID().toString() }
        val hour = item.optInt(KEY_HOUR, 7).coerceIn(0, 23)
        val minute = item.optInt(KEY_MINUTE, 0).coerceIn(0, 59)
        val label = item.optString(KEY_LABEL)
        val enabled = item.optBoolean(KEY_ENABLED, true)
        val ringtoneUri = when {
            item.isNull(KEY_RINGTONE) -> null
            else -> item.optString(KEY_RINGTONE).ifBlank { null }
        }
        val ringFile = when {
            item.isNull(KEY_RING_FILE) -> null
            else -> item.optString(KEY_RING_FILE).ifBlank { null }
        }
        val createdAt = item.optLong(KEY_CREATED_AT, System.currentTimeMillis())
        val repeat = when (item.optString(KEY_REPEAT_TYPE, TYPE_DAILY)) {
            TYPE_WEEKLY -> {
                val daysArray = item.optJSONArray(KEY_WEEKDAYS)
                val days = LinkedHashSet<Int>()
                if (daysArray != null) {
                    for (i in 0 until daysArray.length()) {
                        val day = daysArray.optInt(i)
                        if (day in 1..7) days.add(day)
                    }
                }
                if (days.isEmpty()) RepeatMode.Daily else RepeatMode.Weekly(days)
            }
            TYPE_ONCE -> {
                val date = runCatching { LocalDate.parse(item.optString(KEY_ONCE_DATE)) }.getOrNull()
                if (date == null) RepeatMode.Daily else RepeatMode.Once(date)
            }
            else -> RepeatMode.Daily
        }
        return Alarm(
            id = id,
            hour = hour,
            minute = minute,
            label = label,
            enabled = enabled,
            repeat = repeat,
            ringtoneUri = ringtoneUri,
            createdAt = createdAt,
            ringFile = ringFile,
        )
    }
}
