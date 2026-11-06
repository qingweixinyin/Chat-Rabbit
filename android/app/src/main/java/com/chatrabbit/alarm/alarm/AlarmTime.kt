package com.chatrabbit.alarm.alarm

import com.chatrabbit.alarm.data.Alarm
import com.chatrabbit.alarm.data.RepeatMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * 闹钟时间计算：根据重复规则算出“下一次触发”的绝对时间戳。
 * 全部使用 java.time（minSdk 26 已内置），不依赖 Calendar。
 */
object AlarmTime {

    fun nextTriggerMillis(
        alarm: Alarm,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val time = LocalTime.of(alarm.hour.coerceIn(0, 23), alarm.minute.coerceIn(0, 59))
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

        return when (val repeat = alarm.repeat) {
            RepeatMode.Daily -> {
                val todayTrigger = millisOf(today, time, zone)
                if (todayTrigger > nowMillis) todayTrigger else millisOf(today.plusDays(1), time, zone)
            }

            is RepeatMode.Weekly -> {
                val days = repeat.days
                if (days.isEmpty()) {
                    null
                } else {
                    var result: Long? = null
                    var offset = 0L
                    while (offset <= 7L) {
                        val date = today.plusDays(offset)
                        if (date.dayOfWeek.value in days) {
                            val trigger = millisOf(date, time, zone)
                            if (trigger > nowMillis) {
                                result = trigger
                                break
                            }
                        }
                        offset++
                    }
                    result
                }
            }

            is RepeatMode.Once -> {
                val trigger = millisOf(repeat.date, time, zone)
                if (trigger > nowMillis) trigger else null
            }
        }
    }

    /** "07:05" */
    fun timeText(hour: Int, minute: Int): String =
        String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    /** 重复规则的中文描述，用于列表副标题。 */
    fun repeatText(alarm: Alarm): String = when (val repeat = alarm.repeat) {
        RepeatMode.Daily -> "每天"
        is RepeatMode.Once -> "仅一次 · ${repeat.date}（${weekdayName(repeat.date.dayOfWeek.value)}）"
        is RepeatMode.Weekly -> weekdayText(repeat.days)
    }

    /** 星期多选的中文描述，例如“周一、周三”。 */
    fun weekdayText(days: Set<Int>): String {
        if (days.isEmpty()) return "每天"
        if (days.size == 7) return "每天"
        if (days.size == 5 && days.containsAll(listOf(1, 2, 3, 4, 5))) return "工作日"
        if (days.size == 2 && days.containsAll(listOf(6, 7))) return "周末"
        return days.sorted().joinToString("、") { day -> weekdayName(day) }
    }

    fun weekdayName(day: Int): String = when (day) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        7 -> "周日"
        else -> "周一"
    }

    private fun millisOf(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()
}
