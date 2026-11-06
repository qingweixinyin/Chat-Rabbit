package com.chatrabbit.alarm.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chatrabbit.alarm.data.Alarm
import com.chatrabbit.alarm.data.AlarmRepository
import com.chatrabbit.alarm.data.ApiResult
import com.chatrabbit.alarm.data.AppContainer
import com.chatrabbit.alarm.data.DeviceInfo
import com.chatrabbit.alarm.data.DeviceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 闹钟列表页 ViewModel —— **路由器闹钟表是唯一真源**（v1.8.0）。
 *
 * - 启动 / 进入列表 / 操作后：`GET /api/dev/list` 整表刷新本地镜像（只用于展示/编辑）；
 * - 添加：只调 `POST /api/dev/add`（单条追加），**绝不 clear**；
 * - 编辑 / 开关：原子 `POST /api/dev/upsert`（match 定位旧记录），不再 del+add；
 * - 删除：只调 `POST /api/dev/del {"index": 最新列表下标}`（单条删除）；
 * - 清空：仅「⋮ → 清空设备闹钟」二次确认后调 `POST /api/dev/clear`；
 * - 每次变更后 push + 回读 `dev/list` 刷新 UI。
 */
class AlarmListViewModel(
    private val repository: AlarmRepository,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {

    val alarms: StateFlow<List<Alarm>> = repository.alarms
        .map { list -> list.sortedWith(compareBy({ it.hour }, { it.minute })) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // ---- 列表页快速改铃声（v1.9.0）----
    private val _ringDialog = MutableStateFlow(AlarmRingDialogState())
    val ringDialog: StateFlow<AlarmRingDialogState> = _ringDialog.asStateFlow()

    /** 去重：同一时刻只跑一个后台刷新（切页会反复触发 `LaunchedEffect`） */
    private var refreshJob: Job? = null

    // ---- 多设备（v1.10.0）----
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()

    private val _defaultDeviceId = MutableStateFlow<String?>(null)
    val defaultDeviceId: StateFlow<String?> = _defaultDeviceId.asStateFlow()

    /** 当前选中的设备 id（null = 服务端默认设备） */
    val currentDeviceId: StateFlow<String?> = deviceRepository.currentDeviceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        refreshFromDevice()
        loadDevices()
    }

    /**
     * 后台刷新本地镜像（启动 / 进入列表 / 切页）：**失败静默**，不弹提示。
     * 同一时刻只允许一个在跑（新请求直接忽略）。
     */
    fun refreshFromDevice() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            runOp(silent = true) { deviceRepository.refreshFromDevice() }
        }
    }

    /** 用户主动点「刷新（从设备读取）」：失败要提示。
     *
     * 旧逻辑：`if (refreshJob?.isActive) return`。
     * 问题：进页面时 `LaunchedEffect` 会先触发一次静默刷新，用户看到列表空就点刷新，
     * 这时静默刷新还在飞 → 点击被直接吞掉 → 表现为"设备里有闹钟，App 却怎么刷都读不出来"。
     * 现在：取消在飞的静默刷新，立即执行用户这次请求。
     */
    fun refreshFromDeviceByUser() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            runOp(silent = false) { deviceRepository.refreshFromDevice() }
        }
    }

    // ==================== 多设备切换（v1.10.0） ====================

    /** 拉取设备列表；失败或只有 1 台时静默退化（不显示切换器）。 */
    fun loadDevices() {
        viewModelScope.launch {
            when (val result = deviceRepository.devices()) {
                is ApiResult.Ok -> {
                    _devices.value = result.data.devices
                    _defaultDeviceId.value = result.data.defaultDeviceId
                }

                is ApiResult.Err -> {
                    _devices.value = emptyList()
                    _defaultDeviceId.value = null
                }
            }
        }
    }

    /** 切换当前设备：持久化 → 提示 → 重新拉设备列表 + 强制刷新闹钟镜像。 */
    fun switchDevice(device: DeviceInfo) {
        viewModelScope.launch {
            deviceRepository.setCurrentDeviceId(device.id)
            _message.value = "已切换到 ${device.name}"
            loadDevices()
            // 取消可能还在飞行的旧设备刷新（它返回后也会被 Repository 丢弃）
            refreshJob?.cancel()
            refreshJob = null
            refreshFromDevice()
        }
    }

    /** 开关只切换设备端这条闹钟是否生效（走原子 upsert）。 */
    fun setEnabled(alarm: Alarm, enabled: Boolean) {
        viewModelScope.launch {
            runOp(
                successMessage = if (enabled) {
                    "已开启「${alarm.label.ifBlank { "闹钟" }}」（仅影响玩具设备）"
                } else {
                    "已关闭「${alarm.label.ifBlank { "闹钟" }}」（设备端不再生效）"
                },
            ) {
                deviceRepository.upsertAlarm(previous = alarm, current = alarm.copy(enabled = enabled))
            }
        }
    }

    fun delete(alarm: Alarm) {
        viewModelScope.launch {
            runOp(successMessage = "已删除闹钟（设备端条目已移除）") {
                deviceRepository.deleteAlarm(alarm)
            }
        }
    }

    /** 只让路由器把当前整表重新下发给玩具（不增删、不 clear）。 */
    fun syncNow() {
        viewModelScope.launch {
            runOp(successMessage = "已让路由器重新下发闹钟表") { deviceRepository.pushAlarms() }
        }
    }

    /**
     * 清空设备上的**全部**闹钟（二次确认后调用）。
     * 唯一允许 `POST /api/dev/clear` 的地方；清完后镜像会被刷成空表。
     */
    fun clearDeviceAlarms() {
        if (_syncing.value) return
        viewModelScope.launch {
            runOp(successMessage = "已清空设备上的所有闹钟") { deviceRepository.clearAllAlarms() }
        }
    }

    /**
     * 列表页快速改铃声：点某条闹钟的铃声 → 打开同一套选择器 →
     * `POST /api/dev/ring {"index":n,"file":"…"}`（不重排、不动时间）→ 回读刷新。
     */
    fun openRingDialog(alarm: Alarm) {
        _ringDialog.value = AlarmRingDialogState(alarm = alarm, busy = true)
        viewModelScope.launch {
            val files = when (val filesResult = deviceRepository.listMp3Files()) {
                is ApiResult.Ok -> filesResult.data
                is ApiResult.Err -> {
                    _ringDialog.value = AlarmRingDialogState(
                        alarm = alarm,
                        error = "读取设备音频失败：${filesResult.message}",
                    )
                    return@launch
                }
            }
            val globalRing = when (val voiceResult = deviceRepository.deviceAlarmVoice()) {
                is ApiResult.Ok -> voiceResult.data
                is ApiResult.Err -> null
            }
            _ringDialog.value = AlarmRingDialogState(
                alarm = alarm,
                files = files,
                globalRing = globalRing,
            )
        }
    }

    fun refreshRingFiles() {
        _ringDialog.value.alarm?.let { openRingDialog(it) }
    }

    /** [file] 为 null 表示「跟随全局铃声」。 */
    fun selectRing(file: String?) {
        val current = _ringDialog.value
        val alarm = current.alarm ?: return
        if (current.busy) return
        viewModelScope.launch {
            _ringDialog.value = current.copy(busy = true, error = null)
            runOp(
                successMessage = if (file == null) {
                    "「${alarm.label.ifBlank { "闹钟" }}」已改为跟随全局铃声"
                } else {
                    "「${alarm.label.ifBlank { "闹钟" }}」铃声已改为 $file"
                },
            ) { deviceRepository.setAlarmRing(alarm, file) }
            _ringDialog.value = AlarmRingDialogState()
        }
    }

    fun dismissRingDialog() {
        if (_ringDialog.value.busy) return
        _ringDialog.value = AlarmRingDialogState()
    }

    fun consumeMessage() {
        _message.value = null
    }

    /**
     * 统一执行一次设备操作。
     * [silent] = true 时（后台刷新）失败不弹提示；只有用户主动操作才提示错误。
     */
    private suspend fun runOp(
        successMessage: String? = null,
        silent: Boolean = false,
        op: suspend () -> ApiResult<*>,
    ) {
        _syncing.value = true
        val result = op()
        _syncing.value = false
        when (result) {
            is ApiResult.Ok -> if (successMessage != null) _message.value = successMessage
            is ApiResult.Err -> if (!silent) _message.value = "操作失败：${result.message}"
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AlarmListViewModel(
                    repository = container.alarmRepository,
                    deviceRepository = container.deviceRepository,
                )
            }
        }
    }
}

/**
 * 列表页「快速改铃声」对话框状态。
 * [alarm] 为 null 表示对话框关闭。
 */
data class AlarmRingDialogState(
    val alarm: Alarm? = null,
    val files: List<String> = emptyList(),
    /** 全局默认铃声（alarm_voice），用于「跟随全局铃声」的说明 */
    val globalRing: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

