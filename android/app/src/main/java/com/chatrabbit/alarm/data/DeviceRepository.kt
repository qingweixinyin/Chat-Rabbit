package com.chatrabbit.alarm.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.chatrabbit.alarm.alarm.AlarmTime
import com.chatrabbit.alarm.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 设备仓库：把 API 调用与“当前路由器地址”解耦，UI 层直接调用这里。
 *
 * 设备固件不支持远程音量/灯光控制，因此这里只保留：
 * 状态查询、响铃测试/时长、media 文件管理、点歌、铃声上传、闹钟表读写（路由器为唯一真源）。
 */
class DeviceRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val alarmRepository: AlarmRepository,
) {

    /** 同一时刻只允许一个 `/api/dev/list` 刷新在跑（切页时去重） */
    private val refreshMutex = Mutex()

    // ==================== 查询 ====================

    suspend fun status(): ApiResult<DeviceStatus> = api().status()

    /** 设备端闹钟表（增量同步时用来定位要删除的 index） */
    suspend fun listDeviceAlarms(): ApiResult<List<DeviceAlarm>> = api().devList()

    /** media 目录中的音频文件列表 */
    suspend fun listMediaFiles(): ApiResult<List<String>> = api().files()

    /** media 目录里可作铃声的 **MP3**（设备只支持 MP3）。 */
    suspend fun listMp3Files(): ApiResult<List<String>> = when (val result = listMediaFiles()) {
        is ApiResult.Ok -> ApiResult.Ok(result.data.filter { it.endsWith(".mp3", ignoreCase = true) })
        is ApiResult.Err -> result
    }

    /** 当前设备铃声（GET /api/status 的 alarm_voice）；没设置时为 null。 */
    suspend fun deviceAlarmVoice(): ApiResult<String?> = when (val result = status()) {
        is ApiResult.Ok -> ApiResult.Ok(result.data.alarmVoice)
        is ApiResult.Err -> result
    }

    /** 设置设备铃声（POST /api/alarmvoice {"file": name}）。铃声是给玩具播的，手机不播放。 */
    suspend fun setDeviceAlarmVoice(fileName: String): ApiResult<Unit> = api().setAlarmVoice(fileName)

    /**
     * 删除 media 目录里的一个音频文件。
     * 删的如果是设备当前铃声 / 默认音乐，服务端会分别把 `alarm_voice` / `music_file` 置空
     * （调用方随后刷新 status 即可）。
     */
    suspend fun deleteMediaFile(fileName: String): ApiResult<FileDeleteResult> =
        api().deleteFile(fileName)

    // ==================== 响铃 / 音乐 ====================

    /** 让设备立刻播放它当前的铃声 */
    suspend fun ringTest(): ApiResult<Unit> = api().ringTest()

    /** 设置设备响铃时长（秒，1..120） */
    suspend fun setRingSeconds(seconds: Int): ApiResult<Unit> = api().ring(seconds)

    /** 点歌：让设备立刻播放 media 目录里的某个文件（POST /api/play） */
    suspend fun playMusic(fileName: String): ApiResult<Unit> = api().play(fileName)

    /** 停止播放（POST /api/stop） */
    suspend fun stopMusic(): ApiResult<Unit> = api().stopMusic()

    /** 设为默认音乐 / 下一首（POST /api/music） */
    suspend fun setDefaultMusic(fileName: String): ApiResult<Unit> = api().setMusic(fileName)

    // ==================== 设备闹钟表（路由器是唯一真源，v1.8.0） ====================

    /**
     * 拉取路由器上的**完整**闹钟表，并整体替换本地镜像。
     *
     * App 启动 / 进入闹钟列表 / 每次操作后都调用它，保证本机看到的就是设备侧真实表。
     * 固件只有「整表替换」命令，所以本地必须维护完整表，否则整表下发会抹掉别人的闹钟。
     */
    suspend fun refreshFromDevice(): ApiResult<List<DeviceAlarm>> = withContext(Dispatchers.IO) {
        // 去重：同一时刻只跑一个整表刷新，避免切页时并发多个 /api/dev/list
        refreshMutex.withLock {
            val requestDeviceId = settingsRepository.currentDeviceIdOnce()
            val client = api()
            when (val listed = client.devList()) {
                is ApiResult.Ok -> {
                    // 请求期间用户切了设备 → 丢弃这次结果，避免镜像被旧设备覆盖
                    if (settingsRepository.currentDeviceIdOnce() == requestDeviceId) {
                        alarmRepository.replaceAll(listed.data.map { it.toLocalAlarm() })
                    }
                    ApiResult.Ok(listed.data)
                }

                is ApiResult.Err -> listed
            }
        }
    }

    /**
     * 添加一条闹钟：**只调 `POST /api/dev/add`（单条追加）**，绝不 clear。
     * 返回前会 push 一次并回读 `/api/dev/list` 刷新镜像。
     */
    suspend fun addAlarm(alarm: Alarm): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val client = api()
        val payload = alarm.toDeviceAlarmPayload()
        val added = client.devAdd(
            hour = alarm.hour,
            minute = alarm.minute,
            name = alarm.label.ifBlank { DEFAULT_ALARM_NAME },
            enabled = alarm.enabled,
            weekMask = payload.weekMask,
            onceDate = payload.onceDate,
            ringFile = payload.ringFile,
        )
        if (added is ApiResult.Err) return@withContext added
        // 仅一次闹钟：回读校验 wk=0 且 sd 对应日期正确（失败原因直接带进提示）
        afterMutation(client, verifyOnce = onceExpectationOf(alarm))
    }

    /**
     * 编辑 / 启用开关：**原子 upsert**（`POST /api/dev/upsert`），不再 del+add。
     *
     * `match` 用 [previous] 的「名称 + 时 + 分」定位旧记录，一次请求内替换。
     */
    suspend fun upsertAlarm(previous: Alarm, current: Alarm): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val client = api()
            val payload = current.toDeviceAlarmPayload()
            val updated = client.devUpsert(
                hour = current.hour,
                minute = current.minute,
                name = current.label.ifBlank { DEFAULT_ALARM_NAME },
                enabled = current.enabled,
                weekMask = payload.weekMask,
                onceDate = payload.onceDate,
                ringFile = payload.ringFile,
                matchName = previous.label.ifBlank { DEFAULT_ALARM_NAME },
                matchHour = previous.hour,
                matchMinute = previous.minute,
            )
            if (updated is ApiResult.Err) return@withContext updated
            afterMutation(client, verifyOnce = onceExpectationOf(current))
        }

    /**
     * 删除一条闹钟：按**最新一次** `/api/dev/list` 的 index 调 `POST /api/dev/del`，
     * 绝不 clear。找不到对应条目（可能已被别处删掉）时返回错误。
     */
    suspend fun deleteAlarm(alarm: Alarm): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val client = api()
        val list = when (val listed = client.devList()) {
            is ApiResult.Ok -> listed.data
            is ApiResult.Err -> return@withContext listed
        }
        val label = alarm.label.ifBlank { DEFAULT_ALARM_NAME }
        val target = list.firstOrNull { it.name == label && it.hour == alarm.hour && it.minute == alarm.minute }
            ?: return@withContext ApiResult.Err("设备上已找不到这条闹钟（可能刚被其它地方删除）")

        val deleted = client.devDel(target.index)
        if (deleted is ApiResult.Err) return@withContext deleted
        afterMutation(client)
    }

    /**
     * **清空设备上的全部闹钟**——这是唯一允许调用 `POST /api/dev/clear` 的地方，
     * 调用方必须已做二次确认；本机镜像随后会被刷成空表。
     */
    suspend fun clearAllAlarms(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val client = api()
        val cleared = client.devClear()
        if (cleared is ApiResult.Err) return@withContext cleared
        afterMutation(client)
    }

    /** 只让路由器把当前整表重新下发给玩具（不做增删）。 */
    suspend fun pushAlarms(): ApiResult<Unit> = api().devPush()

    /**
     * 列表页快速改铃声：只改这条闹钟的铃声（`POST /api/dev/ring {"index":n,"file":…}`），
     * 不重排、不动时间；[ringFile] 为 null/空表示改回"跟随全局默认铃声"。
     * index 取自**最新一次** `/api/dev/list`，改完回读刷新镜像。
     */
    suspend fun setAlarmRing(alarm: Alarm, ringFile: String?): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val client = api()
            val list = when (val listed = client.devList()) {
                is ApiResult.Ok -> listed.data
                is ApiResult.Err -> return@withContext listed
            }
            val label = alarm.label.ifBlank { DEFAULT_ALARM_NAME }
            val target = list.firstOrNull {
                it.name == label && it.hour == alarm.hour && it.minute == alarm.minute
            } ?: return@withContext ApiResult.Err("设备上已找不到这条闹钟（可能刚被其它地方删除）")

            val updated = client.devSetRing(target.index, ringFile)
            if (updated is ApiResult.Err) return@withContext updated
            afterMutation(client)
        }

    /**
     * 变更后统一收尾：冗余 push 一次（add/del/upsert/clear 服务端已各自 push，多一次无害）
     * + 回读 `/api/dev/list` 刷新本地镜像，保证 UI 与设备侧一致。
     *
     * 传了 [verifyOnce] 时（仅一次闹钟），还会校验设备返回的 `wk`/`sd` 是否真的写对了。
     */
    private suspend fun afterMutation(
        client: ApiClient,
        verifyOnce: OnceExpectation? = null,
    ): ApiResult<Unit> {
        client.devPush()
        val list = when (val listed = client.devList()) {
            is ApiResult.Ok -> listed.data
            is ApiResult.Err -> return listed
        }
        alarmRepository.replaceAll(list.map { it.toLocalAlarm() })
        val expectation = verifyOnce
        if (expectation != null) {
            val verification = checkOnceWritten(list, expectation)
            if (verification is ApiResult.Err) return verification
        }
        return ApiResult.Ok(Unit)
    }

    // ==================== 仅一次闹钟回读校验 ====================

    /** 期望写入的「仅一次」闹钟：名称 + 时 + 分 + 日期。 */
    private data class OnceExpectation(
        val name: String,
        val hour: Int,
        val minute: Int,
        val date: LocalDate,
    ) {
        /** 该日期对应的单个星期位（DayOfWeek: 1=周一…7=周日 → bit0=周一） */
        val weekMask: Int
            get() = 1 shl (date.dayOfWeek.value - 1)

        /** "周四" 之类，用于错误文案 */
        val weekdayText: String
            get() = AlarmTime.weekdayName(date.dayOfWeek.value)
    }

    private fun onceExpectationOf(alarm: Alarm): OnceExpectation? {
        val once = alarm.repeat as? RepeatMode.Once ?: return null
        return OnceExpectation(
            name = alarm.label.ifBlank { DEFAULT_ALARM_NAME },
            hour = alarm.hour,
            minute = alarm.minute,
            date = once.date,
        )
    }

    /**
     * 校验设备侧确实写成了「仅一次」：
     * - `wk` 等于该日期对应的单个星期位（**兼容旧服务端 `wk == 0`**），或条目带 `once:true`；
     * - `sd` 的**本地日期**等于用户选的日期。
     *
     * 不满足时返回明确原因（含期望/实际 wk、星期、sd 与整表片段）。
     */
    private fun checkOnceWritten(list: List<DeviceAlarm>, expected: OnceExpectation): ApiResult<Unit> {
        val entry = list.firstOrNull {
            it.name == expected.name && it.hour == expected.hour && it.minute == expected.minute
        } ?: return ApiResult.Err(
            "仅一次未生效：设备表里找不到刚写入的「${expected.name}」",
        )
        val actualDate = entry.localDateOrNull()
        val maskOk = entry.once || entry.weekdayMask == expected.weekMask || entry.weekdayMask == 0
        if (!maskOk || actualDate != expected.date) {
            val snippet = list.take(3).joinToString("；") {
                "${it.name}/${it.hour}:${it.minute}/wk=${it.weekdayMask}/sd=${it.startUnixSeconds}/once=${it.once}"
            }
            val reason = buildString {
                append("仅一次未生效（期望 wk=${expected.weekMask}（${expected.weekdayText}）")
                append("，实际 wk=${entry.weekdayMask}")
                if (entry.once) append("，服务端 once=true")
                append("，sd=${entry.startUnixSeconds}")
                append("，期望日期 ${expected.date}")
                actualDate?.let { append("，实际日期 $it") }
                append("）；设备表：$snippet")
            }
            Log.w(TAG, reason)
            return ApiResult.Err(reason)
        }
        return ApiResult.Ok(Unit)
    }

    // ==================== 铃声上传 ====================

    /**
     * 从用户通过 ACTION_OPEN_DOCUMENT 选择的音频 URI 读取字节并上传到路由器，
     * 同时把原文件名作为 `?name=` 查询参数传过去（URL 编码，支持中文/空格/括号）。
     *
     * 设备只支持 **MP3**：非 .mp3 直接返回错误（不浪费一次上传）。
     * 成功时返回服务端**实际保存的文件名**（同名会自动加 `_1` 序号）。
     */
    suspend fun uploadRingtoneFromUri(uriString: String): ApiResult<RingtoneUploadResult> =
        withContext(Dispatchers.IO) {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
                ?: return@withContext ApiResult.Err("铃声 URI 无效，请重新选择铃声")
            val fileName = queryDisplayName(uri) ?: fallbackFileName(uri)
            if (!fileName.endsWith(".mp3", ignoreCase = true)) {
                return@withContext ApiResult.Err("玩具只支持 MP3（请选择 .mp3 文件，当前：$fileName）")
            }
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                return@withContext ApiResult.Err("无法读取所选音频文件（权限可能已失效，请重新选择铃声）")
            }
            api().uploadRingtone(bytes = bytes, fileName = fileName)
        }

    /** 读取 SAF 文件显示名（同时用作上传后的设备端文件名）。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
            }
        }
    }.getOrNull()

    private fun fallbackFileName(uri: Uri): String {
        val raw = uri.lastPathSegment?.substringAfterLast('/')
        return if (raw.isNullOrBlank()) "ringtone.mp3" else raw
    }

    // ==================== 多设备（v1.10.0） ====================

    /** 路由器上登记的全部设备（GET /api/devices）。 */
    suspend fun devices(): ApiResult<DeviceList> = when (val result = api().devices()) {
        is ApiResult.Ok -> {
            ensureCurrentDeviceValid(result.data)
            result
        }

        is ApiResult.Err -> result
    }

    /** 若当前选中的设备已不存在（被删/改名），回落到服务端默认设备。 */
    private suspend fun ensureCurrentDeviceValid(data: DeviceList) {
        val current = settingsRepository.currentDeviceIdOnce() ?: return
        if (data.devices.none { it.id == current }) {
            settingsRepository.setCurrentDeviceId(null)
        }
    }

    /** 当前选中的设备 id（null = 服务端默认设备）；切页/切设备时订阅它。 */
    val currentDeviceId: Flow<String?> = settingsRepository.currentDeviceId

    suspend fun currentDeviceIdOnce(): String? = settingsRepository.currentDeviceIdOnce()

    /** 切换当前设备（null/空 = 回到服务端默认设备），会持久化。 */
    suspend fun setCurrentDeviceId(deviceId: String?) {
        settingsRepository.setCurrentDeviceId(deviceId)
    }

    /** 所有接口自动带当前设备：`X-Device-Id` 头（ApiClient 内部统一注入）。 */
    private suspend fun api(): ApiClient = ApiClient(
        baseUrl = settingsRepository.baseUrlOnce(),
        deviceId = settingsRepository.currentDeviceIdOnce(),
    )

    private companion object {
        const val DEFAULT_ALARM_NAME = "闹钟"
        const val TAG = "DeviceRepository"
    }
}
