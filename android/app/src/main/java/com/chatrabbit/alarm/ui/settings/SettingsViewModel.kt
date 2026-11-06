package com.chatrabbit.alarm.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chatrabbit.alarm.data.ApiResult
import com.chatrabbit.alarm.data.AppContainer
import com.chatrabbit.alarm.data.DeviceInfo
import com.chatrabbit.alarm.data.DeviceRepository
import com.chatrabbit.alarm.data.ProbeOutcome
import com.chatrabbit.alarm.data.RouterDiscovery
import com.chatrabbit.alarm.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val routerDiscovery: RouterDiscovery,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {

    val baseUrl: StateFlow<String> = settingsRepository.baseUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_BASE_URL)

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // ---- 设备管理（v1.10.0）----
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()

    private val _defaultDeviceId = MutableStateFlow<String?>(null)
    val defaultDeviceId: StateFlow<String?> = _defaultDeviceId.asStateFlow()

    private val _devicesLoading = MutableStateFlow(false)
    val devicesLoading: StateFlow<Boolean> = _devicesLoading.asStateFlow()

    val currentDeviceId: StateFlow<String?> = deviceRepository.currentDeviceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        loadDevices()
    }

    /** 拉取设备列表（手动「刷新列表」也会调；失败给提示）。 */
    fun loadDevices() {
        viewModelScope.launch {
            _devicesLoading.value = true
            when (val result = deviceRepository.devices()) {
                is ApiResult.Ok -> {
                    _devices.value = result.data.devices
                    _defaultDeviceId.value = result.data.defaultDeviceId
                }

                is ApiResult.Err -> {
                    // 旧服务端没有 /api/devices（404）→ 静默退化为单设备
                    _devices.value = emptyList()
                    _defaultDeviceId.value = null
                }
            }
            _devicesLoading.value = false
        }
    }

    /** 切换当前设备（持久化，重启保持）。 */
    fun switchDevice(device: DeviceInfo) {
        viewModelScope.launch {
            deviceRepository.setCurrentDeviceId(device.id)
            _message.value = "已切换到 ${device.name}"
            loadDevices()
        }
    }

    /** 恢复服务端默认设备。 */
    fun useDefaultDevice() {
        viewModelScope.launch {
            deviceRepository.setCurrentDeviceId(null)
            _message.value = "已恢复使用服务端默认设备"
            loadDevices()
        }
    }

    /** 只保存（不做网络验证）。输入会自动补 http:// 与 :8890。 */
    fun saveBaseUrl(rawUrl: String) {
        viewModelScope.launch {
            if (rawUrl.isBlank()) {
                _message.value = "请输入路由器地址"
                return@launch
            }
            settingsRepository.setBaseUrl(rawUrl)
            _message.value = "已保存：${settingsRepository.baseUrlOnce()}"
        }
    }

    /**
     * 保存前先做一次 `GET /api/status` 验证：
     * - 成功：写入设置；
     * - 失败：不保存，并给出具体原因（超时 / HTTP 404 / 非 JSON / 不是聊天兔服务）。
     */
    fun saveAndTest(rawUrl: String) {
        viewModelScope.launch {
            if (rawUrl.isBlank()) {
                _message.value = "请输入路由器地址"
                return@launch
            }
            _testing.value = true
            when (val outcome = routerDiscovery.probe(rawUrl, RouterDiscovery.VALIDATE_TIMEOUT_MS)) {
                is ProbeOutcome.Success -> {
                    settingsRepository.setBaseUrl(outcome.baseUrl)
                    _testing.value = false
                    _message.value = if (outcome.status.deviceOnline) {
                        "连接成功，玩具在线（设备 IP ${outcome.status.deviceIp ?: "未知"}，clients=${outcome.status.clients}）"
                    } else {
                        "地址可用并已保存，但玩具当前离线"
                    }
                }

                is ProbeOutcome.Failure -> {
                    _testing.value = false
                    _message.value = "验证失败：${outcome.reason}\n地址未保存，请检查后重试"
                }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    routerDiscovery = container.routerDiscovery,
                    deviceRepository = container.deviceRepository,
                )
            }
        }
    }
}
