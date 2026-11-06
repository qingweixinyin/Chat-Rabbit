package com.chatrabbit.alarm.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** 扫描到的一台 BLE 设备 */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    /** 广播里是否带 0xFFFF 配网服务（优先展示 / 置顶） */
    val hasConfigService: Boolean,
) {
    val displayName: String get() = name.ifBlank { "未知设备" }
}

/** 配网进度步骤（UI 直接显示 label） */
enum class ProvisionStep(val label: String) {
    CONNECTING("连接设备"),
    DISCOVERING("发现 GATT 服务"),
    ENABLING_NOTIFY("开启结果通知"),
    REQUESTING_MTU("协商 MTU"),
    SENDING_SSID("发送 WiFi 名称"),
    SENDING_PASSWORD("发送 WiFi 密码"),
    SENDING_OPENID("发送 openid"),
    WAITING_DEVICE("等待设备返回结果"),
}

/** 配网器对外状态 */
sealed interface ProvisionState {
    data object Idle : ProvisionState
    data class Scanning(val filtered: Boolean) : ProvisionState
    data object ScanFinished : ProvisionState
    data class Running(val step: ProvisionStep) : ProvisionState
    data object Succeeded : ProvisionState
    data class Failed(val reason: String) : ProvisionState
}

/**
 * 图灵 BLE 配网器：扫描 → 连接 → 发现服务 → 开通知 → 协商 MTU → 分片写 FF01 → 解析 FF02。
 *
 * 实现要点：
 * - 所有 GATT 调用都跑在主线程（内部 scope 用 `Dispatchers.Main.immediate`）；
 * - 写操作严格串行：`WRITE_NO_RESPONSE` 没有 `onCharacteristicWrite` 回调，所以每帧后固定 delay 40ms；
 * - `writeCharacteristic` 在 API 33+ 用带 writeType 的新签名，低版本走 `characteristic.value` 老签名；
 * - 分片大小 `chunk = mtu - 3 - 6`（MTU 247 → 238；协商失败用 23 → 14）。
 */
class BleProvisioner(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    private val _state = MutableStateFlow<ProvisionState>(ProvisionState.Idle)
    val state: StateFlow<ProvisionState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanTimeoutJob: Job? = null

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private var connectionDeferred: CompletableDeferred<Boolean>? = null
    private var servicesDeferred: CompletableDeferred<Boolean>? = null
    private var mtuDeferred: CompletableDeferred<Int>? = null
    private var notificationsDeferred: CompletableDeferred<Boolean>? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private var events: Channel<DeviceEvent>? = null

    private var provisionJob: Job? = null

    /**
     * 兜底判定：由 ViewModel 注入，返回"路由器是否已经看到这台设备上线"。
     * 固件只有在 WiFi + 云端 MQTT 全通后才回 B0 成功帧，WiFi 通了但云端没通时
     * 光等通知会一直超时；用路由器状态兜底可以避免"明明连上了却报失败"。
     */
    var onlineProbe: (suspend () -> Boolean)? = null

    // ==================== 对外能力查询 ====================

    fun isBluetoothSupported(): Boolean = bluetoothAdapter() != null

    fun isBluetoothEnabled(): Boolean {
        val adapter = bluetoothAdapter() ?: return false
        return runCatching { adapter.isEnabled }.getOrDefault(false)
    }

    fun hasScanPermission(): Boolean = BlePermissions.granted(appContext)

    // ==================== 扫描 ====================

    /**
     * 开始扫描。
     * @param filtered true = 只保留广播带 0xFFFF 配网服务的设备；false = 全量扫描（兜底）
     */
    fun startScan(filtered: Boolean) {
        if (provisionJob?.isActive == true) {
            appendLog("配网进行中，暂不扫描")
            return
        }
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            setFailed("此设备不支持蓝牙")
            return
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            setFailed("蓝牙未开启，请先打开蓝牙")
            return
        }
        if (!hasScanPermission()) {
            setFailed("缺少蓝牙扫描权限")
            return
        }
        val leScanner = runCatching { adapter.bluetoothLeScanner }.getOrNull()
        if (leScanner == null) {
            setFailed("无法获取 BLE 扫描器")
            return
        }

        stopScan()
        _devices.value = emptyList()
        _state.value = ProvisionState.Scanning(filtered)
        appendLog(if (filtered) "开始扫描（过滤 0xFFFF 配网服务）…" else "开始全量扫描（不做服务过滤）…")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                appendLog("扫描失败，errorCode=$errorCode")
                _state.value = ProvisionState.Failed("扫描启动失败（errorCode=$errorCode）")
            }
        }

        scanner = leScanner
        scanCallback = callback

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters: List<ScanFilter> = if (filtered) {
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(LeNetCfgProtocol.SERVICE_UUID))
                    .build(),
            )
        } else {
            emptyList()
        }

        val started = runCatching { leScanner.startScan(filters, settings, callback) }
        if (started.isFailure) {
            setFailed("启动扫描失败：${started.exceptionOrNull()?.message ?: "未知错误"}")
            return
        }

        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            stopScan()
            if (_state.value is ProvisionState.Scanning) {
                _state.value = ProvisionState.ScanFinished
                appendLog("扫描结束，共发现 ${_devices.value.size} 个设备")
            }
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val callback = scanCallback
        val leScanner = scanner
        if (callback != null && leScanner != null) {
            runCatching { leScanner.stopScan(callback) }
        }
        scanCallback = null
        scanner = null
        if (_state.value is ProvisionState.Scanning) {
            _state.value = ProvisionState.ScanFinished
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        val hasConfigService = result.scanRecord?.serviceUuids
            ?.any { it.uuid == LeNetCfgProtocol.SERVICE_UUID } == true
        val name = runCatching { result.scanRecord?.deviceName ?: device.name }
            .getOrNull()
            .orEmpty()

        val item = BleDevice(
            name = name,
            address = address,
            rssi = result.rssi,
            hasConfigService = hasConfigService,
        )
        _devices.update { current ->
            val merged = current.filterNot { it.address == address }.toMutableList()
            merged.add(item)
            merged.sortedWith(
                compareByDescending<BleDevice> { it.hasConfigService }
                    .thenByDescending { it.rssi },
            )
        }
    }

    // ==================== 配网 ====================

    fun provision(device: BleDevice, ssid: String, password: String) {
        if (ssid.isBlank()) {
            setFailed("WiFi 名称不能为空")
            return
        }
        // 固件侧缓冲：ssid[33] / pwd[65]（含结尾 0），超长会被静默截断 → 必连不上
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        if (ssidBytes.size > 32) {
            setFailed("SSID 超过 32 字节（中文约 10 个字），设备会截断后找不到网络，请改短")
            return
        }
        val pwdBytes = password.toByteArray(Charsets.UTF_8)
        if (pwdBytes.isNotEmpty() && (pwdBytes.size < 8 || pwdBytes.size > 63)) {
            setFailed("WiFi 密码长度需 8–63 字节（当前 ${pwdBytes.size} 字节）")
            return
        }
        if (ssid != ssid.trim()) {
            appendLog("⚠ SSID 首尾有空格，将原样发送（固件不会去掉）")
        }
        if (provisionJob?.isActive == true) {
            appendLog("已有配网任务正在进行")
            return
        }
        stopScan()
        provisionJob = scope.launch { runProvision(device, ssid, password) }
    }

    fun cancelProvision() {
        provisionJob?.cancel()
        provisionJob = null
    }

    /** 页面销毁时调用：停止扫描、断开 GATT、取消协程。 */
    fun release() {
        runCatching { cancelProvision() }
        runCatching { stopScan() }
        closeGatt()
        scope.cancel()
    }

    private suspend fun runProvision(device: BleDevice, ssid: String, password: String) {
        _logs.value = emptyList()
        appendLog("目标设备：${device.displayName}（${device.address}）")
        closeGatt()
        events = Channel(Channel.UNLIMITED)

        try {
            val adapter = bluetoothAdapter() ?: throw ProvisionException("此设备不支持蓝牙")
            if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
                throw ProvisionException("蓝牙未开启")
            }

            setStep(ProvisionStep.CONNECTING)
            appendLog("正在连接…")
            val remote = runCatching { adapter.getRemoteDevice(device.address) }
                .getOrElse { throw ProvisionException("设备地址无效：${device.address}") }

            if (!connect(remote)) {
                throw ProvisionException("GATT 连接失败（设备可能已离开配网模式）")
            }
            appendLog("已连接")

            setStep(ProvisionStep.DISCOVERING)
            appendLog("正在发现服务…")
            if (!discoverServices()) {
                throw ProvisionException("未发现 0xFFFF 配网服务，请确认设备处于配网模式")
            }
            appendLog("已找到配网服务（FF01 写入 / FF02 通知）")

            setStep(ProvisionStep.ENABLING_NOTIFY)
            enableNotifications()
            appendLog("已开启结果通知")

            setStep(ProvisionStep.REQUESTING_MTU)
            val mtu = requestMtu()
            val chunkSize = (mtu - 3 - 6).coerceAtLeast(1)
            appendLog("MTU = $mtu，每帧数据 $chunkSize 字节")
            appendLog("写入特征属性 = 0x${Integer.toHexString(writeCharacteristic?.properties ?: 0)}")

            // 不发送"开场帧" B0 01 00 00 4F。
            // 固件 le_net_cfg_turing.c 里收到该帧会 complete=0，并且在
            // CONFIG_POWER_ON_ENABLE_BLE=0 的构建上直接 ble_module_enable(0)
            // —— 也就是把蓝牙关掉，随后所有写都会失败（实测"SSID 第1帧失败×3"）。
            // 协议里它本来就是"可选"，这里改为发一个空密码帧之外不做任何清场；
            // 设备端在 CCCD 写入时已经 clear_turing_net_cfg_info()。
            delay(150)

            setStep(ProvisionStep.SENDING_SSID)
            sendType(LeNetCfgProtocol.TYPE_SSID, ssid.toByteArray(Charsets.UTF_8), chunkSize)

            setStep(ProvisionStep.SENDING_PASSWORD)
            sendType(LeNetCfgProtocol.TYPE_PASSWORD, password.toByteArray(Charsets.UTF_8), chunkSize)

            setStep(ProvisionStep.SENDING_OPENID)
            sendType(LeNetCfgProtocol.TYPE_OPENID, ByteArray(0), chunkSize)

            setStep(ProvisionStep.WAITING_DEVICE)
            appendLog("数据发送完成，等待设备结果（最长 ${RESULT_TIMEOUT_MS / 1000} 秒）…")

            when (awaitResult(ssid, password, chunkSize)) {
                true -> {
                    appendLog("配网成功，设备正在连接 WiFi")
                    _state.value = ProvisionState.Succeeded
                }

                false -> throw ProvisionException(
                    "设备报告配网失败（WiFi 连不上：请检查密码、2.4GHz 频段与信号强度）",
                )
            }
        } catch (cancellation: CancellationException) {
            appendLog("已取消配网")
            _state.value = ProvisionState.Idle
            throw cancellation
        } catch (t: Throwable) {
            val reason = (t as? ProvisionException)?.message
                ?: t.message
                ?: "未知错误"
            appendLog("配网失败：$reason")
            _state.value = ProvisionState.Failed(reason)
        } finally {
            events?.close()
            events = null
            // 断开前留一点缓冲：连续"写失败 + 立刻断开"会让部分手机的蓝牙控制器
            // 进入异常状态（表现为之后连文件传输都失败），需要关蓝牙/重启才能恢复。
            delay(CLOSE_GRACE_MS)
            closeGatt()
        }
    }

    // ==================== GATT 流程 ====================

    private suspend fun connect(device: BluetoothDevice): Boolean {
        connectionDeferred = CompletableDeferred()
        servicesDeferred = CompletableDeferred()

        val created = runCatching {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull() ?: return false

        gatt = created
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connectionDeferred?.await() } ?: false
    }

    private suspend fun discoverServices(): Boolean =
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { servicesDeferred?.await() } ?: false

    private suspend fun enableNotifications() {
        val currentGatt = gatt ?: return
        val currentNotify = notifyCharacteristic ?: return
        notificationsDeferred = CompletableDeferred()

        runCatching { currentGatt.setCharacteristicNotification(currentNotify, true) }
        val cccd = currentNotify.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            appendLog("未找到 CCCD 描述符，跳过订阅步骤")
            return
        }
        if (!writeDescriptor(currentGatt, cccd)) {
            appendLog("订阅通知写入失败，继续尝试（部分固件默认推送）")
        }
        withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { notificationsDeferred?.await() }
    }

    private suspend fun requestMtu(): Int {
        val currentGatt = gatt ?: return DEFAULT_MTU
        mtuDeferred = CompletableDeferred()
        val requested = runCatching { currentGatt.requestMtu(REQUESTED_MTU) }.getOrDefault(false)
        if (!requested) {
            appendLog("MTU 请求被拒绝，使用默认 23")
            return DEFAULT_MTU
        }
        val mtu = withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuDeferred?.await() } ?: DEFAULT_MTU
        return mtu.coerceAtLeast(DEFAULT_MTU)
    }

    private suspend fun sendType(type: Int, data: ByteArray, chunkSize: Int) {
        val frames = LeNetCfgProtocol.splitIntoFrames(type, data, chunkSize)
        appendLog(
            "发送${LeNetCfgProtocol.typeName(type)}：${data.size} 字节，共 ${frames.size} 帧",
        )
        frames.forEachIndexed { index, frame ->
            if (!writeFrame(frame)) {
                throw ProvisionException(
                    "蓝牙写入被手机拒绝（${LeNetCfgProtocol.typeName(type)} 第 ${index + 1} 帧，已重试 $WRITE_RETRIES 次）。" +
                        "请先把手机蓝牙关掉再打开（必要时重启手机），并把玩具断电重启后重试。",
                )
            }
        }
    }

    /**
     * 写一帧到 FF01。
     *
     * 关键教训（1.10.2 之前的真实故障）：
     * 原来固定用 WRITE_NO_RESPONSE + 40ms 间隔。手机蓝牙控制器的发送队列一旦满，
     * `writeCharacteristic()` 会直接返回 false（日志里就是"写入失败：A0 02 ..."），
     * 于是整次配网在这里中断。
     * 现在改成：
     *  - 特征支持 Write（有响应）时，用 WRITE_TYPE_DEFAULT 并**等 onCharacteristicWrite 回调**，
     *    天然形成流控；
     *  - 只支持 Write No Response 时，用固定间隔，但**每帧最多重试 3 次**；
     *  - 写完留一点间隔，避免下一帧排队被拒。
     */
    private suspend fun writeFrame(frame: ByteArray): Boolean {
        val characteristic = writeCharacteristic ?: return false

        val supportsWrite = (characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE) != 0

        repeat(WRITE_RETRIES) { attempt ->
            // 每次都重新取 gatt：写失败期间设备可能已经断开
            val currentGatt = gatt ?: return false
            // 第一次先试"有响应写"（哪怕特性没声明可写），能拿到明确的状态码；
            // 失败再退回"无响应写"。JL 的 FF01 在多数固件上其实两种都吃。
            var ok = false
            if (supportsWrite || attempt == 0) {
                ok = writeWithResponse(currentGatt, characteristic, frame)
                if (ok) {
                    delay(FRAME_INTERVAL_MS)
                    return true
                }
            }
            if (gatt != null) {
                ok = writeNoResponse(currentGatt, characteristic, frame)
                if (ok) {
                    delay(FRAME_INTERVAL_MS)
                    return true
                }
            }
            appendLog(
                "写入失败(第${attempt + 1}/${WRITE_RETRIES}次，" +
                    if (supportsWrite) "有响应" else "无响应" +
                    ")：${LeNetCfgProtocol.toHex(frame)}",
            )
            delay(WRITE_RETRY_DELAY_MS)
        }
        return false
    }

    private suspend fun writeWithResponse(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray,
    ): Boolean {
        writeDeferred = CompletableDeferred()
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        }
        if (!queued) {
            writeDeferred = null
            return false
        }
        val result = withTimeoutOrNull(WRITE_RESPONSE_TIMEOUT_MS) { writeDeferred?.await() } ?: false
        writeDeferred = null
        return result
    }

    private suspend fun writeNoResponse(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        frame: ByteArray,
    ): Boolean {
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        }
        delay(FRAME_INTERVAL_MS)
        return queued
    }

    private suspend fun awaitResult(ssid: String, password: String, chunkSize: Int): Boolean {
        val channel = events ?: throw ProvisionException("内部错误：结果通道未初始化")
        val deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MS
        var receivedAll = false
        var probeMiss = 0

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                throw ProvisionException(
                    if (receivedAll) {
                        "设备已收到 WiFi 信息并开始连接，但 ${RESULT_TIMEOUT_MS / 1000} 秒内路由器没看到它上线。" +
                            "常见原因：密码错误、玩具连到了别的网络、信号太弱，或路由器限制（MAC 过滤/上网控制）。"
                    } else {
                        "等待设备结果超时（${RESULT_TIMEOUT_MS / 1000} 秒）：设备没有回应任何通知。"
                    },
                )
            }
            // 每 4 秒一轮：既等设备通知，也在"收齐数据"后主动问路由器设备有没有上线。
            // 原因：固件只有在「WiFi + 云端 MQTT」全都通了之后才回 B0 成功帧
            // （turing_mqtt.c:276-280），WiFi 通了但云端没通时 App 会一直等到超时。
            val window = minOf(remaining, PROBE_WINDOW_MS)
            val event = withTimeoutOrNull(window) { channel.receive() }
            if (event == null) {
                if (receivedAll) {
                    val online = runCatching { onlineProbe?.invoke() }.getOrNull() ?: false
                    if (online) {
                        appendLog("路由器已经看到设备上线 → 判定配网成功（设备未回成功帧，走兜底判定）")
                        return true
                    }
                    probeMiss++
                    if (probeMiss % 3 == 1) {
                        appendLog("等待设备联网…（路由器还没看到设备上线）")
                    }
                }
                continue
            }

            when (event) {
                is DeviceEvent.ReceivedAll -> {
                    if (!receivedAll) {
                        receivedAll = true
                        appendLog("设备已收齐数据，开始连接 WiFi…")
                    }
                }

                is DeviceEvent.Resend -> {
                    val payload = when (event.type) {
                        LeNetCfgProtocol.TYPE_SSID -> ssid.toByteArray(Charsets.UTF_8)
                        LeNetCfgProtocol.TYPE_PASSWORD -> password.toByteArray(Charsets.UTF_8)
                        else -> ByteArray(0)
                    }
                    appendLog("设备请求重发${LeNetCfgProtocol.typeName(event.type)}")
                    sendType(event.type, payload, chunkSize)
                }

                is DeviceEvent.Succeeded -> return true

                is DeviceEvent.Failed -> return false

                is DeviceEvent.Unknown -> {
                    // 成功后会分片推送 mac/api_key/deviceid，这里只需记录
                    appendLog("忽略未知通知：${event.hex}")
                }
            }
        }
    }

    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLog("GATT 已连接（status=$status），开始发现服务")
                connectionDeferred?.complete(true)
                runCatching { gatt.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog("GATT 已断开（status=$status）")
                connectionDeferred?.complete(false)
                servicesDeferred?.complete(false)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesDeferred?.complete(false)
                return
            }
            val service = gatt.getService(LeNetCfgProtocol.SERVICE_UUID)
            writeCharacteristic =
                service?.getCharacteristic(LeNetCfgProtocol.WRITE_CHARACTERISTIC_UUID)
            notifyCharacteristic =
                service?.getCharacteristic(LeNetCfgProtocol.NOTIFY_CHARACTERISTIC_UUID)
            servicesDeferred?.complete(
                writeCharacteristic != null && notifyCharacteristic != null,
            )
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mtuDeferred?.complete(
                if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU,
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            notificationsDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        /** API 33+ 的通知回调 */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(value)
        }

        /** API 26~32 的通知回调 */
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleNotification(value)
        }
    }

    private fun handleNotification(value: ByteArray) {
        val hex = LeNetCfgProtocol.toHex(value)
        appendLog("收到通知：$hex")
        events?.trySend(LeNetCfgProtocol.parseNotification(value))
    }

    // ==================== 工具 ====================

    private fun bluetoothAdapter(): BluetoothAdapter? =
        runCatching {
            appContext.getSystemService(BluetoothManager::class.java)?.adapter
        }.getOrNull()

    private fun closeGatt() {
        val current = gatt
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        connectionDeferred = null
        servicesDeferred = null
        mtuDeferred = null
        notificationsDeferred = null
        writeDeferred = null
        if (current != null) {
            runCatching { current.disconnect() }
            runCatching { current.close() }
        }
    }

    private fun setStep(step: ProvisionStep) {
        _state.value = ProvisionState.Running(step)
    }

    private fun setFailed(reason: String) {
        appendLog(reason)
        _state.value = ProvisionState.Failed(reason)
    }

    private fun appendLog(line: String) {
        val timestamp = LocalTime.now().format(LOG_TIME_FORMATTER)
        _logs.update { current ->
            val next = current + "[$timestamp] $line"
            if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
        }
        Log.d(TAG, line)
    }

    private class ProvisionException(message: String) : Exception(message)

    private companion object {
        const val TAG = "BleProvisioner"

        const val DEFAULT_MTU = 23
        const val REQUESTED_MTU = 247

        /** 写完一帧后的固定间隔（有响应时是回调返回后再等这么久） */
        const val FRAME_INTERVAL_MS = 50L
        /** 单帧写失败后的重试次数 / 重试间隔 */
        const val WRITE_RETRIES = 3
        const val WRITE_RETRY_DELAY_MS = 120L
        /** 有响应写：等 onCharacteristicWrite 的超时 */
        const val WRITE_RESPONSE_TIMEOUT_MS = 3_000L
        /** 断开前留一点时间，避免手机蓝牙控制器被"瞬时断开"卡住 */
        const val CLOSE_GRACE_MS = 300L

        const val SCAN_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val NOTIFY_TIMEOUT_MS = 2_000L
        const val MTU_TIMEOUT_MS = 3_000L
        /**
         * 等设备联网结果。固件的成功帧要等「WiFi + 云端 MQTT」都通了才发
         * （turing_mqtt.c:276-280），45 秒经常不够，改成 90 秒；
         * 同时每 [PROBE_WINDOW_MS] 问一次路由器，设备一上线就提前判定成功。
         */
        const val RESULT_TIMEOUT_MS = 90_000L
        const val PROBE_WINDOW_MS = 4_000L

        const val MAX_LOG_LINES = 200

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val LOG_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    }
}
