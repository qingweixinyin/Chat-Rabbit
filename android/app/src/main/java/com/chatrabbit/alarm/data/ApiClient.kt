package com.chatrabbit.alarm.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder

/**
 * 路由器 REST API 客户端（OkHttp + org.json，无 Retrofit）。
 *
 * 约定的接口见 README，全部返回 JSON，成功时含 "ok": true。
 *
 * 注意：设备固件已裁掉音量/灯光控制（`/api/ctl` 实测无反应），
 * 本 App 不再暴露相关入口；`ctl()` 仅作为协议兼容保留。
 */
class ApiClient(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpProvider.client,
    /** 当前选中的设备 id；空 = 服务端默认设备（旧行为） */
    private val deviceId: String? = null,
) {

    private val root: String = normalizeBaseUrl(baseUrl)

    /** 统一给所有请求带上当前设备（服务端所有接口都支持 `X-Device-Id` 头）。 */
    private fun applyDevice(builder: Request.Builder): Request.Builder =
        if (deviceId.isNullOrBlank()) builder else builder.header(DEVICE_HEADER, deviceId)

    // ==================== 多设备（v1.5.0 服务端） ====================

    /** 列出路由器上登记的全部设备（GET /api/devices）。 */
    suspend fun devices(): ApiResult<DeviceList> = withContext(Dispatchers.IO) {
        when (val result = getJson("/api/devices")) {
            is ApiResult.Ok -> ApiResult.Ok(parseDeviceListResponse(result.data))
            is ApiResult.Err -> result
        }
    }

    private fun parseDeviceListResponse(json: JSONObject): DeviceList {
        val array = json.optJSONArray("devices")
        val list = ArrayList<DeviceInfo>(array?.length() ?: 0)
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").ifBlank { item.optString("device_id") }
                if (id.isBlank()) continue
                list.add(
                    DeviceInfo(
                        id = id,
                        name = item.optString("name").ifBlank { id },
                        online = item.optBoolean("online", false),
                        ip = item.optString("ip").ifBlank { null },
                        lastConnect = item.optString("last_connect").ifBlank { null },
                        alarmCount = item.optInt("alarm_count", 0),
                        alarmVoice = item.optString("alarm_voice").ifBlank { null },
                        musicFile = item.optString("music_file").ifBlank { null },
                    ),
                )
            }
        }
        return DeviceList(
            defaultDeviceId = json.optString("default").ifBlank { null },
            devices = list,
        )
    }

    // ==================== 设备闹钟表 ====================

    suspend fun devList(): ApiResult<List<DeviceAlarm>> = withContext(Dispatchers.IO) {
        when (val result = getJson("/api/dev/list")) {
            is ApiResult.Ok -> ApiResult.Ok(parseDeviceList(result.data))
            is ApiResult.Err -> result
        }
    }

    /**
     * 新增一条设备侧闹钟（**只追加，绝不 clear 整表**；一条记录 + `wk` 掩码）。
     *
     * - 重复闹钟：发 `wk` 位掩码（bit0=周一 … bit6=周日），服务端写成一条 `DevAlarm(WK=掩码)`；
     * - 仅一次：只带 `onceDate`（"yyyy-MM-dd"）。
     *
     * @param weekMask 星期位掩码；`63` = 每天，`31` = 周一~周五；仅一次时传 null
     * @param onceDate 非空表示“仅一次”
     */
    suspend fun devAdd(
        hour: Int,
        minute: Int,
        name: String,
        enabled: Boolean,
        weekMask: Int?,
        onceDate: String?,
        ringFile: String?,
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        postUnit(
            "/api/dev/add",
            buildAlarmBody(hour, minute, name, enabled, weekMask, onceDate, ringFile),
        )
    }

    /**
     * 原子更新一条设备侧闹钟（`POST /api/dev/upsert`）。
     *
     * 用 `match` 里的「旧名称 + 旧时 + 旧分」定位旧记录，一次请求内完成替换，
     * 不会出现「先删后加」造成的短暂缺条窗口。
     */
    suspend fun devUpsert(
        hour: Int,
        minute: Int,
        name: String,
        enabled: Boolean,
        weekMask: Int?,
        onceDate: String?,
        ringFile: String?,
        matchName: String,
        matchHour: Int,
        matchMinute: Int,
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val body = buildAlarmBody(hour, minute, name, enabled, weekMask, onceDate, ringFile)
        body.put(
            "match",
            JSONObject().apply {
                put("name", matchName)
                put("hour", matchHour)
                put("minute", matchMinute)
            },
        )
        postUnit("/api/dev/upsert", body)
    }

    /**
     * 只改某条**已存在**闹钟的铃声（`POST /api/dev/ring {"index":n,"file":"xxx.mp3"}`）。
     * 不重排、不动时间；`file` 为空字符串表示改回"跟随全局默认铃声"。
     */
    suspend fun devSetRing(index: Int, file: String?): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("index", index)
        body.put("file", file?.takeIf { it.isNotBlank() } ?: "")
        postUnit("/api/dev/ring", body)
    }

    /** `/api/dev/add` 与 `/api/dev/upsert` 共用的闹钟字段。 */
    private fun buildAlarmBody(
        hour: Int,
        minute: Int,
        name: String,
        enabled: Boolean,
        weekMask: Int?,
        onceDate: String?,
        ringFile: String?,
    ): JSONObject = JSONObject().apply {
        put("hour", hour)
        put("minute", minute)
        put("name", name)
        put("enabled", enabled)
        if (!onceDate.isNullOrBlank()) {
            // 一次性闹钟：`onceDate` + 该日期对应的**单个星期位**。
            // 注意：固件把 wk 当星期选择掩码，wk=0 会“一天都没选”→ 永不触发，所以不能发 0。
            put("onceDate", onceDate)
            put("wk", (weekMask ?: 0).coerceIn(0, WEEK_MASK_ALL_DAYS))
        } else {
            put("wk", (weekMask ?: WEEK_MASK_EVERY_DAY).coerceIn(0, WEEK_MASK_ALL_DAYS))
        }
        // 这条闹钟独立的铃声；空 = 跟随全局默认（alarm_voice）
        bodyRing(this, ringFile)
    }

    /** 写 `ring` 字段：非空才写，空/ null 不写（服务端即按“跟随全局”处理）。 */
    private fun bodyRing(body: JSONObject, ringFile: String?) {
        val ring = ringFile?.takeIf { it.isNotBlank() }
        if (ring != null) body.put("ring", ring)
    }

    suspend fun devDel(index: Int): ApiResult<Unit> = withContext(Dispatchers.IO) {
        postUnit("/api/dev/del", JSONObject().put("index", index))
    }

    suspend fun devToggle(index: Int): ApiResult<Unit> = withContext(Dispatchers.IO) {
        postUnit("/api/dev/toggle", JSONObject().put("index", index))
    }

    suspend fun devClear(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        postUnit("/api/dev/clear", JSONObject())
    }

    /** 让路由器把整张闹钟表重新下发给设备。 */
    suspend fun devPush(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        postUnit("/api/dev/push", JSONObject())
    }

    // ==================== 设备控制 / 响铃 ====================

    /**
     * 协议保留：operate 0=音量减 1=音量加 2=关灯 3=开灯 7=查询状态。
     * 实测聊天兔固件不支持音量/灯光，App 界面不再使用；如需扩展可调用。
     */
    suspend fun ctl(operate: Int): ApiResult<Unit> = withContext(Dispatchers.IO) {
        postUnit("/api/ctl", JSONObject().put("operate", operate))
    }

    /** 让设备立刻播放当前铃声。 */
    suspend fun ringTest(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        postUnit("/api/ringtest", JSONObject())
    }

    /** 设置设备响铃时长（秒，1..120）。 */
    suspend fun ring(seconds: Int): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val safeSeconds = seconds.coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS)
        postUnit("/api/ring", JSONObject().put("seconds", safeSeconds))
    }

    /** 点歌：让设备立刻播放 media 目录里的某个文件（POST /api/play {"file": name}）。 */
    suspend fun play(fileName: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        postUnit("/api/play", JSONObject().put("file", fileName))
    }

    /** 停止播放（POST /api/stop {}）。 */
    suspend fun stopMusic(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        postUnit("/api/stop", JSONObject())
    }

    /** 设为默认音乐 / 下一首（POST /api/music {"file": name}）。 */
    suspend fun setMusic(fileName: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        postUnit("/api/music", JSONObject().put("file", fileName))
    }

    /**
     * 设置**设备铃声**（`POST /api/alarmvoice {"file": name}`）。
     * 铃声是给玩具播的，手机不播放；服务端把它存为 `alarm_voice`。
     */
    suspend fun setAlarmVoice(fileName: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        postUnit("/api/alarmvoice", JSONObject().put("file", fileName))
    }

    /** 列出 media 目录里的铃声文件（GET /api/files → {"files":["tone.mp3",...]}）。 */
    suspend fun files(): ApiResult<List<String>> = withContext(Dispatchers.IO) {
        when (val result = getJson("/api/files")) {
            is ApiResult.Ok -> {
                val array = result.data.optJSONArray("files")
                val files = ArrayList<String>()
                if (array != null) {
                    for (index in 0 until array.length()) {
                        val name = array.optString(index)
                        if (name.isNotBlank()) files.add(name)
                    }
                }
                ApiResult.Ok(files)
            }

            is ApiResult.Err -> result
        }
    }

    /**
     * 把音频原始字节上传为设备铃声。
     *
     * 服务端（v1.4.2+）会**保留原始文件名**（同名自动加序号），并返回
     * `{"ok":true,"file":"实际保存名","size":N}`；保存后还会自动设为全局 `alarm_voice`。
     *
     * @param fileName 文件名，作为 `?name=` 查询参数（URL 编码，支持中文/空格/括号）
     */
    suspend fun uploadRingtone(
        bytes: ByteArray,
        fileName: String?,
        mimeType: String = "audio/mpeg",
    ): ApiResult<RingtoneUploadResult> = withContext(Dispatchers.IO) {
        val url = buildString {
            append(buildUrl("/api/ringtone/upload"))
            if (!fileName.isNullOrBlank()) {
                append("?name=")
                append(URLEncoder.encode(fileName, "UTF-8"))
            }
        }
        val request = applyDevice(
            Request.Builder()
                .url(url)
                .post(bytes.toRequestBody(mimeType.toMediaType())),
        ).build()
        when (val result = executeWithRetry(request)) {
            is ApiResult.Ok -> {
                val json = result.data
                ApiResult.Ok(
                    RingtoneUploadResult(
                        // 服务端返回实际保存名（可能带 _1 序号）；老服务端没有该字段时回退到原文件名
                        fileName = json.optString("file").ifBlank { fileName ?: "unknown.mp3" },
                        sizeBytes = json.optInt("size", bytes.size),
                    ),
                )
            }

            is ApiResult.Err -> result
        }
    }

    // ==================== 文件管理 / 状态 ====================

    /**
     * 删除 media 目录里的一个铃声文件（`POST /api/file/delete`）。
     *
     * 文件名**只放在 JSON body 里**，不拼 URL——中文 / 空格 / 括号由 JSON 编码处理，
     * 服务端拿到的是原始字符串。删除当前正在使用的铃声时，服务端会把 `alarm_voice` 置空。
     */
    suspend fun deleteFile(name: String): ApiResult<FileDeleteResult> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", name)
        when (val result = postJson("/api/file/delete", body)) {
            is ApiResult.Ok -> {
                val json = result.data
                ApiResult.Ok(
                    FileDeleteResult(
                        deletedName = json.optString("deleted").ifBlank { name },
                        freedBytes = json.optInt("freed", 0),
                    ),
                )
            }

            is ApiResult.Err -> result
        }
    }

    suspend fun status(): ApiResult<DeviceStatus> = withContext(Dispatchers.IO) {
        when (val result = getJson("/api/status")) {
            is ApiResult.Ok -> ApiResult.Ok(parseDeviceStatus(result.data))
            is ApiResult.Err -> result
        }
    }

    // ==================== 内部实现 ====================

    private fun buildUrl(path: String): String = root + path

    private suspend fun getJson(path: String): ApiResult<JSONObject> {
        val request = applyDevice(
            Request.Builder()
                .url(buildUrl(path))
                .get(),
        ).build()
        return executeWithRetry(request)
    }

    private suspend fun postJson(path: String, body: JSONObject): ApiResult<JSONObject> {
        val request = applyDevice(
            Request.Builder()
                .url(buildUrl(path))
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE)),
        ).build()
        return executeWithRetry(request)
    }

    private suspend fun postUnit(path: String, body: JSONObject): ApiResult<Unit> =
        when (val result = postJson(path, body)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Err -> result
        }

    /**
     * 网络请求：**网络类失败自动重试 1 次（间隔 300ms）**，仍失败才报错。
     * HTTP 错误 / 业务错误（ok:false）不重试，属于确定性失败。
     */
    private suspend fun executeWithRetry(request: Request): ApiResult<JSONObject> {
        val first = executeOnce(request)
        if (!first.second) return first.first
        delay(RETRY_DELAY_MS)
        return executeOnce(request).first
    }

    /** 执行一次；第二个返回值表示「是否值得重试」（连接/超时等网络类异常）。 */
    private fun executeOnce(request: Request): Pair<ApiResult<JSONObject>, Boolean> = try {
        client.newCall(request).execute().use { response -> handleResponse(response) to false }
    } catch (e: SocketTimeoutException) {
        ApiResult.Err("网络请求超时：${e.message ?: e.javaClass.simpleName}") to true
    } catch (e: IOException) {
        ApiResult.Err("网络请求失败：${e.message ?: e.javaClass.simpleName}") to true
    } catch (e: SecurityException) {
        ApiResult.Err("网络权限被拒绝：${e.message ?: e.javaClass.simpleName}") to false
    } catch (e: Exception) {
        ApiResult.Err("请求异常：${e.message ?: e.javaClass.simpleName}") to false
    }

    private fun handleResponse(response: Response): ApiResult<JSONObject> {
        val bodyText = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = if (bodyText.isBlank()) "" else "：" + bodyText.take(160)
            return ApiResult.Err("HTTP ${response.code}$detail")
        }
        if (bodyText.isBlank()) return ApiResult.Ok(JSONObject())
        val json = runCatching { JSONObject(bodyText) }.getOrNull()
            ?: return ApiResult.Err("响应不是合法 JSON")
        if (!json.optBoolean("ok", true)) {
            val message = json.optString("msg")
                .ifBlank { json.optString("error") }
                .ifBlank { json.optString("err") }
                .ifBlank { json.optString("reason") }
                .ifBlank { "设备接口返回失败" }
            return ApiResult.Err(message)
        }
        return ApiResult.Ok(json)
    }

    private fun parseDeviceList(json: JSONObject): List<DeviceAlarm> {
        val array = json.optJSONArray("list") ?: return emptyList()
        val result = ArrayList<DeviceAlarm>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            result.add(
                DeviceAlarm(
                    index = index,
                    name = item.optString("na"),
                    hour = item.optInt("th"),
                    minute = item.optInt("tm"),
                    startUnixSeconds = item.optLong("sd"),
                    weekdayMask = item.optInt("wk"),
                    enabled = item.optInt("s", 0) == 1,
                    // 每条闹钟独立的铃声（服务端 rv）；空 = 跟随全局 alarm_voice
                    ringFile = item.optString("rv").ifBlank { null },
                    // 服务端标记“仅一次”（v1.4.8+ 返回；旧服务端没有该字段）
                    once = item.optBoolean("once", false),
                ),
            )
        }
        return result
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 网络类失败重试前的等待时间（只重试 1 次） */
        const val RETRY_DELAY_MS = 300L

        /** 多设备：服务端所有接口都支持这个头（也支持 `?dev=` 查询参数） */
        const val DEVICE_HEADER = "X-Device-Id"
    }
}
