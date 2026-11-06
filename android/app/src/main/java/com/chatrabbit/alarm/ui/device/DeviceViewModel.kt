package com.chatrabbit.alarm.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chatrabbit.alarm.data.ApiResult
import com.chatrabbit.alarm.data.AppContainer
import com.chatrabbit.alarm.data.DEFAULT_RING_SECONDS
import com.chatrabbit.alarm.data.DeviceInfo
import com.chatrabbit.alarm.data.DeviceRepository
import com.chatrabbit.alarm.data.MAX_RING_SECONDS
import com.chatrabbit.alarm.data.MIN_RING_SECONDS
import com.chatrabbit.alarm.data.ProbeOutcome
import com.chatrabbit.alarm.data.RouterDiscovery
import com.chatrabbit.alarm.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设备页状态。
 * 聊天兔固件不支持远程音量/灯光控制，所以这里只保留：
 * 连接信息（地址 / 在线 / clients / device_ip / last_connect）、
 * 当前铃声、响铃时长、响铃测试、铃声上传。
 */
data class DeviceUiState(
    val baseUrl: String = SettingsRepository.DEFAULT_BASE_URL,
    val online: Boolean? = null,
    val clients: Int = 0,
    /** 路由器记录的最近一次玩具 IP（服务端 device_ip） */
    val deviceIp: String? = null,
    /** 最近连接时间（服务端 last_connect） */
    val lastConnect: String? = null,
    /** 设备当前铃声文件名（服务端 alarm_voice） */
    val alarmVoice: String? = null,
    /** 设备当前响铃时长（服务端 ring_seconds） */
    val deviceRingSeconds: Int = DEFAULT_RING_SECONDS,
    /** 本地选中的响铃时长档位 */
    val selectedRingSeconds: Int = DEFAULT_RING_SECONDS,
    /** media 目录里的音频文件（GET /api/files），点歌与删除都用它 */
    val mediaFiles: List<String> = emptyList(),
    /** 默认音乐 / 下一首（服务端 music_file） */
    val musicFile: String? = null,
    /** 正在播放 / 设为默认的文件名（行内进度） */
    val musicActionFile: String? = null,
    /** 停止播放请求进行中 */
    val musicStopping: Boolean = false,
    /** 正在删除的文件名（用于禁用重复点击 + 行内进度） */
    val deletingName: String? = null,
    val busy: Boolean = false,
    /** 自动检测进行中 */
    val autoDetecting: Boolean = false,
    val autoDetectProgress: String? = null,
    /** 「修改地址」对话框 */
    val addressDialogVisible: Boolean = false,
    val addressInput: String = "",
    val addressValidating: Boolean = false,
    val addressError: String? = null,
    val message: String? = null,
    // ---- 多设备（v1.10.0）----
    /** 路由器上登记的全部设备；<=1 台时不显示切换器 */
    val devices: List<DeviceInfo> = emptyList(),
    /** 服务端默认设备 id */
    val defaultDeviceId: String? = null,
    /** 当前选中的设备 id（null = 服务端默认） */
    val currentDeviceId: String? = null,
)

class DeviceViewModel(
    private val deviceRepository: DeviceRepository,
    private val settingsRepository: SettingsRepository,
    private val routerDiscovery: RouterDiscovery,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceUiState())
    val uiState: StateFlow<DeviceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.baseUrl.collect { url ->
                _uiState.update { it.copy(baseUrl = url) }
            }
        }
        viewModelScope.launch {
            deviceRepository.currentDeviceId.collect { id ->
                _uiState.update { it.copy(currentDeviceId = id) }
            }
        }
        refreshSilently()
        loadDevices()
    }

    // ==================== 多设备切换（v1.10.0） ====================

    /** 拉取设备列表；失败时静默退化（不显示切换器）。 */
    fun loadDevices() {
        viewModelScope.launch {
            when (val result = deviceRepository.devices()) {
                is ApiResult.Ok -> _uiState.update {
                    it.copy(
                        devices = result.data.devices,
                        defaultDeviceId = result.data.defaultDeviceId,
                    )
                }

                is ApiResult.Err -> _uiState.update {
                    it.copy(devices = emptyList(), defaultDeviceId = null)
                }
            }
        }
    }

    /** 切换设备：持久化 → 提示 → 重拉列表并刷新状态/文件。 */
    fun switchDevice(device: DeviceInfo) {
        viewModelScope.launch {
            deviceRepository.setCurrentDeviceId(device.id)
            _uiState.update {
                it.copy(currentDeviceId = device.id, message = "已切换到 ${device.name}")
            }
            loadDevices()
            refreshSilently()
        }
    }

    // ==================== 连接信息 ====================

    /** 拉取 /api/status 与 /api/files（用户主动刷新：会提示错误）。 */
    fun refresh() {
        viewModelScope.launch { loadStatus(showMessage = true) }
    }

    /** 后台刷新（首次进入页面等）：**失败静默**，不弹提示。 */
    fun refreshSilently() {
        viewModelScope.launch { loadStatus(showMessage = false) }
    }

    private suspend fun loadStatus(showMessage: Boolean) {
        _uiState.update { it.copy(busy = true) }
        val statusResult = deviceRepository.status()
        val filesResult = deviceRepository.listMediaFiles()
        val files = if (filesResult is ApiResult.Ok) filesResult.data else _uiState.value.mediaFiles

        _uiState.update { state ->
            when (statusResult) {
                is ApiResult.Ok -> state.copy(
                    busy = false,
                    online = statusResult.data.deviceOnline,
                    clients = statusResult.data.clients,
                    deviceIp = statusResult.data.deviceIp,
                    lastConnect = statusResult.data.lastConnect,
                    alarmVoice = statusResult.data.alarmVoice,
                    deviceRingSeconds = statusResult.data.ringSeconds,
                    selectedRingSeconds = statusResult.data.ringSeconds,
                    mediaFiles = files,
                    musicFile = statusResult.data.musicFile,
                    message = when {
                        !showMessage -> state.message
                        statusResult.data.deviceOnline -> null
                        else -> "路由器可达，但玩具设备离线"
                    },
                )

                is ApiResult.Err -> state.copy(
                    busy = false,
                    online = false,
                    mediaFiles = files,
                    message = if (showMessage) "状态查询失败：${statusResult.message}" else state.message,
                )
            }
        }
    }

    // ==================== 修改地址 / 自动检测 ====================

    fun openAddressDialog() {
        _uiState.update {
            it.copy(
                addressDialogVisible = true,
                addressInput = it.baseUrl,
                addressError = null,
            )
        }
    }

    fun dismissAddressDialog() {
        if (_uiState.value.addressValidating) return
        _uiState.update { it.copy(addressDialogVisible = false, addressError = null) }
    }

    fun setAddressInput(value: String) {
        _uiState.update { it.copy(addressInput = value, addressError = null) }
    }

    /** 校验并保存「修改地址」对话框里填的地址（支持只填 IP）。 */
    fun confirmAddress() {
        val raw = _uiState.value.addressInput
        if (raw.isBlank()) {
            _uiState.update { it.copy(addressError = "请输入路由器地址") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(addressValidating = true, addressError = null) }
            when (val outcome = routerDiscovery.probe(raw, RouterDiscovery.VALIDATE_TIMEOUT_MS)) {
                is ProbeOutcome.Success -> {
                    settingsRepository.setBaseUrl(outcome.baseUrl)
                    _uiState.update {
                        it.copy(
                            addressValidating = false,
                            addressDialogVisible = false,
                            addressError = null,
                            baseUrl = outcome.baseUrl,
                        )
                    }
                    loadStatus(showMessage = false)
                    _uiState.update { it.copy(message = "已切换路由器地址：${outcome.baseUrl}") }
                }

                is ProbeOutcome.Failure -> _uiState.update {
                    it.copy(addressValidating = false, addressError = outcome.reason)
                }
            }
        }
    }

    /** 依次探测常见网段，第一个可用地址自动保存。 */
    fun autoDetect() {
        if (_uiState.value.autoDetecting) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(autoDetecting = true, autoDetectProgress = "开始自动检测…", message = null)
            }
            val found = routerDiscovery.detect(
                onProgress = { index, total, url ->
                    _uiState.update {
                        it.copy(autoDetectProgress = "正在探测 ${index + 1}/$total：$url")
                    }
                },
            )
            if (found == null) {
                _uiState.update {
                    it.copy(
                        autoDetecting = false,
                        autoDetectProgress = null,
                        message = "没有探测到可用地址，请点「修改地址」手动填写",
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        autoDetecting = false,
                        autoDetectProgress = null,
                        baseUrl = found.baseUrl,
                    )
                }
                loadStatus(showMessage = false)
                _uiState.update { it.copy(message = "已切换到 ${found.baseUrl}") }
            }
        }
    }

    // ==================== 设备功能 ====================

    /** 响铃测试：POST /api/ringtest */
    fun ringTest() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val result = deviceRepository.ringTest()
            _uiState.update {
                it.copy(
                    busy = false,
                    message = when (result) {
                        is ApiResult.Ok -> "已让设备播放当前铃声"
                        is ApiResult.Err -> "响铃测试失败：${result.message}"
                    },
                )
            }
        }
    }

    /** 设置响铃时长：POST /api/ring {"seconds":N} */
    fun selectRingSeconds(seconds: Int) {
        val safeSeconds = seconds.coerceIn(MIN_RING_SECONDS, MAX_RING_SECONDS)
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, selectedRingSeconds = safeSeconds) }
            when (val result = deviceRepository.setRingSeconds(safeSeconds)) {
                is ApiResult.Ok -> _uiState.update {
                    it.copy(
                        busy = false,
                        deviceRingSeconds = safeSeconds,
                        message = "设备响铃时长已设为 $safeSeconds 秒",
                    )
                }

                is ApiResult.Err -> _uiState.update {
                    it.copy(busy = false, message = "设置响铃时长失败：${result.message}")
                }
            }
        }
    }

    /** 上传本地音频到路由器 media 目录（成功后服务端会把它设为全局铃声）。 */
    fun uploadRingtone(uriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            when (val result = deviceRepository.uploadRingtoneFromUri(uriString)) {
                is ApiResult.Ok -> {
                    loadStatus(showMessage = false)
                    val uploaded = result.data
                    val voice = _uiState.value.alarmVoice
                    _uiState.update {
                        it.copy(
                            busy = false,
                            message = if (voice != null) {
                                "已上传 ${uploaded.fileName}（${uploaded.sizeBytes} 字节），设备当前铃声：$voice"
                            } else {
                                "已上传 ${uploaded.fileName}（${uploaded.sizeBytes} 字节）"
                            },
                        )
                    }
                }

                is ApiResult.Err -> _uiState.update {
                    // 原样显示服务端 err 原文，便于排查
                    it.copy(busy = false, message = "上传失败：${result.message}")
                }
            }
        }
    }

    // ==================== 点歌 / 音乐 ====================

    /** 点歌：POST /api/play {"file": name}，让设备立刻播放。 */
    fun playMusic(fileName: String) {
        if (fileName.isBlank()) return
        if (_uiState.value.musicActionFile != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(musicActionFile = fileName) }
            when (val result = deviceRepository.playMusic(fileName)) {
                is ApiResult.Ok -> _uiState.update {
                    it.copy(musicActionFile = null, message = "已下发播放：$fileName")
                }

                is ApiResult.Err -> _uiState.update {
                    it.copy(musicActionFile = null, message = "播放失败：${result.message}")
                }
            }
        }
    }

    /** 停止播放：POST /api/stop */
    fun stopMusic() {
        if (_uiState.value.musicStopping) return
        viewModelScope.launch {
            _uiState.update { it.copy(musicStopping = true) }
            when (val result = deviceRepository.stopMusic()) {
                is ApiResult.Ok -> _uiState.update {
                    it.copy(musicStopping = false, message = "已停止播放")
                }

                is ApiResult.Err -> _uiState.update {
                    it.copy(musicStopping = false, message = "停止失败：${result.message}")
                }
            }
        }
    }

    /** 设为默认音乐（设备自动播放用）：POST /api/music {"file": name} */
    fun setDefaultMusic(fileName: String) {
        if (fileName.isBlank()) return
        if (_uiState.value.musicActionFile != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(musicActionFile = fileName) }
            when (val result = deviceRepository.setDefaultMusic(fileName)) {
                is ApiResult.Ok -> {
                    _uiState.update { it.copy(musicActionFile = null) }
                    loadStatus(showMessage = false)
                    _uiState.update { it.copy(message = "已设为默认音乐：$fileName") }
                }

                is ApiResult.Err -> _uiState.update {
                    it.copy(musicActionFile = null, message = "设为默认失败：${result.message}")
                }
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /**
     * 删除 media 目录里的音频文件（POST /api/file/delete）。
     *
     * - 文件名放在 JSON body 里，中文 / 空格 / 括号不需要额外转义；
     * - 成功后重新拉 `status` + `files`：被删的是当前铃声 / 默认音乐时，
     *   服务端已把 `alarm_voice` / `music_file` 置空，界面会自动显示「未设置」；
     * - 失败时把服务端返回的 `error` 文案原样展示。
     */
    fun deleteMediaFile(fileName: String) {
        if (fileName.isBlank()) return
        if (_uiState.value.deletingName != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(deletingName = fileName) }
            when (val result = deviceRepository.deleteMediaFile(fileName)) {
                is ApiResult.Ok -> {
                    _uiState.update { it.copy(deletingName = null) }
                    loadStatus(showMessage = false)
                    val deleted = result.data.deletedName.ifBlank { fileName }
                    _uiState.update { it.copy(message = "已删除 $deleted") }
                }

                is ApiResult.Err -> _uiState.update {
                    it.copy(deletingName = null, message = "删除失败：${result.message}")
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DeviceViewModel(
                    deviceRepository = container.deviceRepository,
                    settingsRepository = container.settingsRepository,
                    routerDiscovery = container.routerDiscovery,
                )
            }
        }
    }
}
