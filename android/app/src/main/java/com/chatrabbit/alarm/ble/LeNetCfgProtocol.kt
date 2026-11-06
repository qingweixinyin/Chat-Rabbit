package com.chatrabbit.alarm.ble

import java.util.Locale
import java.util.UUID

/**
 * 图灵 BLE 配网协议（固件来源：`apps/common/ble/le_net_cfg_turing.c` / `.h`）。
 *
 * ## GATT
 * - Service `0000FFFF-0000-1000-8000-00805F9B34FB`
 * - Char `FF01` 写入（WRITE_NO_RESPONSE）
 * - Char `FF02` 通知（NOTIFY）
 * - Char `2A00` 设备名（只读）
 *
 * ## 手机 → 设备（写 FF01），数据帧
 * ```
 * [0]    0xA0 固定
 * [1]    类型：0x01=SSID 0x02=密码 0x03=openid
 * [2]    本次序号 seq（从 0 开始）
 * [3]    总序号 total（最后一帧的 seq；单帧时 seq=0,total=0）
 * [4]    本帧数据长度 len
 * [5..]  数据
 * [last] CRC = (0x100 - (前面所有字节之和 and 0xFF)) and 0xFF
 * ```
 * 设备判定“该类数据收完”的条件是 `seq == 0 || seq == total`；
 * 三类（SSID / 密码 / openid）全部收完后才触发 WiFi 连接。
 *
 * 开场帧（可选，清空设备端状态）：`B0 01 00 00 4F`
 * openid 允许空数据帧：`A0 03 00 00 00 5D`
 *
 * ## 设备 → 手机（FF02 通知）
 * ```
 * A0 01 00 00 5F   数据全部收到，开始连 WiFi
 * A0 00 01 00 5F   要求重发 SSID（type=1）
 * A0 00 02 00 5E   要求重发密码（type=2）
 * A0 00 03 00 5D   要求重发 openid（type=3）
 * B0 ... buf[6]==0x50   配网失败（WiFi 连不上）
 * B0 01 01 ...          成功（后续分片发 mac / api_key / deviceid，App 忽略内容）
 * ```
 */
object LeNetCfgProtocol {

    val SERVICE_UUID: UUID = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
    val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    val DEVICE_NAME_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")

    const val TYPE_SSID: Int = 0x01
    const val TYPE_PASSWORD: Int = 0x02
    const val TYPE_OPENID: Int = 0x03

    /** 失败帧里表示“WiFi 连接失败”的状态字节位置与取值 */
    private const val FAILURE_STATUS_INDEX = 6
    private const val FAILURE_STATUS_VALUE = 0x50

    private const val MARKER_DATA = 0xA0
    private const val MARKER_CTRL = 0xB0

    /** 开场帧：`B0 01 00 00 4F` */
    val OPENING_FRAME: ByteArray = byteArrayOf(0xB0.toByte(), 0x01, 0x00, 0x00, 0x4F)

    fun typeName(type: Int): String = when (type) {
        TYPE_SSID -> "SSID"
        TYPE_PASSWORD -> "WiFi 密码"
        TYPE_OPENID -> "openid"
        else -> "type=$type"
    }

    /** 构造一帧数据帧（含校验尾字节）。 */
    fun buildDataFrame(type: Int, seq: Int, total: Int, data: ByteArray): ByteArray {
        val frame = ByteArray(HEADER_SIZE + data.size + 1)
        frame[0] = MARKER_DATA.toByte()
        frame[1] = type.toByte()
        frame[2] = seq.toByte()
        frame[3] = total.toByte()
        frame[4] = data.size.toByte()
        data.copyInto(destination = frame, destinationOffset = HEADER_SIZE)
        frame[frame.size - 1] = crc(frame, 0, frame.size - 1)
        return frame
    }

    /**
     * 把一段数据切成若干帧。
     * - 空数据 → 单帧（seq=0, total=0, len=0）
     * - 否则 seq 从 0 递增，total = 最后一帧的 seq
     */
    fun splitIntoFrames(type: Int, data: ByteArray, chunkSize: Int): List<ByteArray> {
        val chunk = chunkSize.coerceAtLeast(1)
        if (data.isEmpty()) return listOf(buildDataFrame(type, 0, 0, ByteArray(0)))

        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val end = (offset + chunk).coerceAtMost(data.size)
            frames.add(buildDataFrame(type, frames.size, 0, data.copyOfRange(offset, end)))
            offset = end
        }
        val total = frames.size - 1
        // 回填 total
        return frames.mapIndexed { index, frame ->
            frame[3] = total.toByte()
            frame[frame.size - 1] = crc(frame, 0, frame.size - 1)
            frame
        }
    }

    /** 解析设备通知。 */
    fun parseNotification(raw: ByteArray): DeviceEvent {
        if (raw.isEmpty()) return DeviceEvent.Unknown("")
        return when (raw[0].toInt() and 0xFF) {
            MARKER_DATA -> parseDataNotification(raw)
            MARKER_CTRL -> parseCtrlNotification(raw)
            else -> DeviceEvent.Unknown(toHex(raw))
        }
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(separator = " ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

    // ==================== 内部 ====================

    private fun parseDataNotification(raw: ByteArray): DeviceEvent {
        // A0 01 ... = 数据全部收到，开始连 WiFi
        if (raw.size >= 2 && (raw[1].toInt() and 0xFF) == 0x01) return DeviceEvent.ReceivedAll
        // A0 00 0X ... = 要求重发 type=0X
        if (raw.size >= 3 && (raw[1].toInt() and 0xFF) == 0x00) {
            val type = raw[2].toInt() and 0xFF
            if (type in TYPE_SSID..TYPE_OPENID) return DeviceEvent.Resend(type)
        }
        return DeviceEvent.Unknown(toHex(raw))
    }

    private fun parseCtrlNotification(raw: ByteArray): DeviceEvent {
        if (raw.size > FAILURE_STATUS_INDEX &&
            (raw[FAILURE_STATUS_INDEX].toInt() and 0xFF) == FAILURE_STATUS_VALUE
        ) {
            return DeviceEvent.Failed
        }
        if (raw.size >= 3 &&
            (raw[1].toInt() and 0xFF) == 0x01 &&
            (raw[2].toInt() and 0xFF) == 0x01
        ) {
            return DeviceEvent.Succeeded
        }
        return DeviceEvent.Unknown(toHex(raw))
    }

    private fun crc(bytes: ByteArray, from: Int, until: Int): Byte {
        var sum = 0
        for (index in from until until) {
            sum += bytes[index].toInt() and 0xFF
        }
        return ((0x100 - (sum and 0xFF)) and 0xFF).toByte()
    }

    private const val HEADER_SIZE = 5
}

/** 设备 → 手机的通知语义 */
sealed interface DeviceEvent {
    /** `A0 01 ...` 数据收齐，设备开始连 WiFi */
    data object ReceivedAll : DeviceEvent

    /** `A0 00 0X ...` 要求重发 type=0X */
    data class Resend(val type: Int) : DeviceEvent

    /** `B0 01 01 ...` 配网成功 */
    data object Succeeded : DeviceEvent

    /** `B0 ... buf[6]==0x50` 配网失败 */
    data object Failed : DeviceEvent

    /** 未识别的通知（仅记录日志） */
    data class Unknown(val hex: String) : DeviceEvent
}
