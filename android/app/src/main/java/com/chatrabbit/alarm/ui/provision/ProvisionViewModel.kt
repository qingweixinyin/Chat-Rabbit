package com.chatrabbit.alarm.ui.provision

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chatrabbit.alarm.ble.BleDevice
import com.chatrabbit.alarm.ble.BleProvisioner
import com.chatrabbit.alarm.ble.ProvisionState
import com.chatrabbit.alarm.ble.currentWifiSsid
import com.chatrabbit.alarm.data.ApiResult
import com.chatrabbit.alarm.data.AppContainer
import com.chatrabbit.alarm.data.DeviceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProvisionUiState(
    val selectedAddress: String? = null,
    val ssid: String = "",
    val password: String = "",
    val ssidPrefilled: Boolean = false,
    /** 配网成功后轮询 /api/status 的提示文案 */
    val onlineMessage: String? = null,
    val pollingOnline: Boolean = false,
)

class ProvisionViewModel(
    private val provisioner: BleProvisioner,
    private val deviceRepository: DeviceRepository,
    initialSsid: String?,
) : ViewModel() {

    val devices: StateFlow<List<BleDevice>> = provisioner.devices
    val state: StateFlow<ProvisionState> = provisioner.state
    val logs: StateFlow<List<String>> = provisioner.logs

    private val _ui = MutableStateFlow(
        ProvisionUiState(
            ssid = initialSsid.orEmpty(),
            ssidPrefilled = !initialSsid.isNullOrBlank(),
        ),
    )
    val ui: StateFlow<ProvisionUiState> = _ui.asStateFlow()

    private var fallbackScanJob: Job? = null
    private var onlinePollJob: Job? = null

    init {
        // 配网成功后自动确认设备是否上线
        viewModelScope.launch {
            provisioner.state.collect { state ->
                if (state is ProvisionState.Succeeded) {
                    pollDeviceOnline()
                }
            }
        }
    }

    fun hasScanPermission(): Boolean = provisioner.hasScanPermission()

    fun isBluetoothEnabled(): Boolean = provisioner.isBluetoothEnabled()

    fun isBluetoothSupported(): Boolean = provisioner.isBluetoothSupported()

    /**
     * 先按 0xFFFF 服务过滤扫描；5 秒内一个都没扫到就退化为全量扫描。
     */
    fun startScan() {
        onlinePollJob?.cancel()
        _ui.update { it.copy(onlineMessage = null, pollingOnline = false) }
        provisioner.startScan(filtered = true)

        fallbackScanJob?.cancel()
        fallbackScanJob = viewModelScope.launch {
            delay(FALLBACK_SCAN_DELAY_MS)
            if (provisioner.devices.value.isEmpty()) {
                provisioner.startScan(filtered = false)
            }
        }
    }

    fun stopScan() {
        fallbackScanJob?.cancel()
        fallbackScanJob = null
        provisioner.stopScan()
    }

    fun selectDevice(device: BleDevice) {
        _ui.update { it.copy(selectedAddress = device.address, onlineMessage = null) }
    }

    fun setSsid(value: String) {
        _ui.update { it.copy(ssid = value, ssidPrefilled = false) }
    }

    fun setPassword(value: String) {
        _ui.update { it.copy(password = value) }
    }

    fun startProvision() {
        val current = _ui.value
        val address = current.selectedAddress
        if (address == null) {
            return
        }
        if (current.ssid.isBlank()) {
            return
        }
        val device = provisioner.devices.value.firstOrNull { it.address == address } ?: return

        stopScan()
        onlinePollJob?.cancel()
        _ui.update { it.copy(onlineMessage = null, pollingOnline = false) }

        // 兜底判定：先记下"配网前已经在线"的设备，配网过程中如果有「新设备上线」，
        // 就算设备没回 B0 成功帧也判定成功（固件要等云端 MQTT 通了才回帧）。
        provisioner.onlineProbe = null
        viewModelScope.launch {
            val baseline = runCatching {
                val result = deviceRepository.devices()
                if (result is ApiResult.Ok) {
                    result.data.devices.filter { it.online }.map { it.id }.toSet()
                } else {
                    emptySet()
                }
            }.getOrDefault(emptySet())
            provisioner.onlineProbe = {
                val result = deviceRepository.devices()
                result is ApiResult.Ok && result.data.devices.any { it.online && it.id !in baseline }
            }
        }
        provisioner.provision(device = device, ssid = current.ssid, password = current.password)
    }

    fun cancelProvision() {
        provisioner.cancelProvision()
    }

    /** 配网成功后最多轮询 60 秒（20 次 × 3 秒）确认设备上线。 */
    private fun pollDeviceOnline() {
        onlinePollJob?.cancel()
        onlinePollJob = viewModelScope.launch {
            _ui.update {
                it.copy(pollingOnline = true, onlineMessage = "配网成功，正在确认真机是否上线…")
            }
            repeat(POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                when (val result = deviceRepository.status()) {
                    is ApiResult.Ok -> {
                        if (result.data.deviceOnline) {
                            _ui.update {
                                it.copy(
                                    pollingOnline = false,
                                    onlineMessage = "设备已上线（已连接客户端 ${result.data.clients}）",
                                )
                            }
                            return@launch
                        }
                    }

                    is ApiResult.Err -> Unit
                }
            }
            _ui.update {
                it.copy(
                    pollingOnline = false,
                    onlineMessage = "60 秒内未检测到设备上线，可稍后在「设备」页点刷新状态",
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        fallbackScanJob?.cancel()
        onlinePollJob?.cancel()
        provisioner.release()
    }

    companion object {
        private const val FALLBACK_SCAN_DELAY_MS = 5_000L
        private const val POLL_INTERVAL_MS = 3_000L
        private const val POLL_ATTEMPTS = 20

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProvisionViewModel(
                    provisioner = BleProvisioner(container.context),
                    deviceRepository = container.deviceRepository,
                    initialSsid = currentWifiSsid(container.context),
                )
            }
        }
    }
}
