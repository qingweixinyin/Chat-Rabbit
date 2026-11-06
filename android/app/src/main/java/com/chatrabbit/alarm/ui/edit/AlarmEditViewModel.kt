package com.chatrabbit.alarm.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chatrabbit.alarm.alarm.AlarmTime
import com.chatrabbit.alarm.data.Alarm
import com.chatrabbit.alarm.data.AlarmRepository
import com.chatrabbit.alarm.data.ApiResult
import com.chatrabbit.alarm.data.AppContainer
import com.chatrabbit.alarm.data.DeviceRepository
import com.chatrabbit.alarm.data.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/** 编辑页的三种重复模式 */
enum class RepeatUiMode { DAILY, WEEKLY, ONCE }

data class AlarmEditUiState(
    val hour: Int = 7,
    val minute: Int = 0,
    val label: String = "",
    val mode: RepeatUiMode = RepeatUiMode.DAILY,
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val onceDate: LocalDate = LocalDate.now().plusDays(1),
    val enabled: Boolean = true,
    val loading: Boolean = false,
    val saving: Boolean = false,
    // ---- 本条闹钟的铃声（v1.9.0 起每条独立） ----
    val ringtoneDialogVisible: Boolean = false,
    /** media 目录里可选的 MP3（含内置铃声音） */
    val deviceRingtoneFiles: List<String> = emptyList(),
    /** 全局默认铃声（GET /api/status 的 alarm_voice），用于「跟随全局铃声」的说明 */
    val currentDeviceRingtone: String? = null,
    /** 本条闹钟选中的铃声；null = 跟随全局默认铃声 */
    val selectedRingFile: String? = null,
    val ringtoneBusy: Boolean = false,
    val ringtoneError: String? = null,
)

/**
 * 新建 / 编辑 **设备闹钟**。
 *
 * 本页不做任何本地调度：保存 = 写入 DataStore（设备闹钟表的镜像）+ 增量同步到路由器
 * （一条记录 + `wk` 位掩码；新增只 `devAdd`、编辑走原子 `devUpsert`，见 `DeviceRepository.addAlarm/upsertAlarm`）。
 * 到点由玩具自己响，手机不响铃、不通知、不亮屏。
 */
class AlarmEditViewModel(
    private val repository: AlarmRepository,
    private val deviceRepository: DeviceRepository,
    private val alarmId: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlarmEditUiState(loading = alarmId != null))
    val uiState: StateFlow<AlarmEditUiState> = _uiState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private var createdAt: Long = System.currentTimeMillis()

    /** 当前编辑的闹钟 id；新建时首次保存后生成，避免“重试保存”产生重复闹钟。 */
    private var editingId: String? = alarmId

    init {
        if (alarmId != null) {
            viewModelScope.launch { load(alarmId) }
        }
    }

    private suspend fun load(id: String) {
        val alarm = repository.getAlarm(id)
        if (alarm == null) {
            _uiState.update { it.copy(loading = false) }
            _message.value = "闹钟不存在，可能已被删除"
            return
        }
        createdAt = alarm.createdAt
        val repeat = alarm.repeat
        _uiState.update {
            it.copy(
                hour = alarm.hour,
                minute = alarm.minute,
                label = alarm.label,
                mode = when (repeat) {
                    RepeatMode.Daily -> RepeatUiMode.DAILY
                    is RepeatMode.Weekly -> RepeatUiMode.WEEKLY
                    is RepeatMode.Once -> RepeatUiMode.ONCE
                },
                weekdays = (repeat as? RepeatMode.Weekly)?.days ?: setOf(1, 2, 3, 4, 5),
                onceDate = (repeat as? RepeatMode.Once)?.date ?: LocalDate.now().plusDays(1),
                enabled = alarm.enabled,
                selectedRingFile = alarm.ringFile,
                loading = false,
            )
        }
    }

    fun setTime(hour: Int, minute: Int) {
        _uiState.update { it.copy(hour = hour.coerceIn(0, 23), minute = minute.coerceIn(0, 59)) }
    }

    fun setLabel(value: String) {
        _uiState.update { it.copy(label = value) }
    }

    fun setMode(mode: RepeatUiMode) {
        _uiState.update { state ->
            val date = if (mode == RepeatUiMode.ONCE && !state.onceDate.isAfter(LocalDate.now())) {
                LocalDate.now().plusDays(1)
            } else {
                state.onceDate
            }
            state.copy(mode = mode, onceDate = date)
        }
    }

    fun toggleWeekday(day: Int) {
        if (day !in 1..7) return
        _uiState.update { state ->
            val days = state.weekdays.toMutableSet()
            if (!days.add(day)) days.remove(day)
            state.copy(weekdays = days)
        }
    }

    fun setOnceDate(date: LocalDate) {
        _uiState.update { it.copy(onceDate = date) }
    }

    /** 启用开关：只影响设备端这条闹钟是否生效（手机不响铃）。 */
    fun setEnabled(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
    }

    // ==================== 本条闹钟的铃声（v1.9.0 起每条独立） ====================

    /** 打开铃声选择：拉取 media 目录里的 MP3 + 全局默认铃声（alarm_voice）。 */
    fun openRingtoneDialog() {
        _uiState.update { it.copy(ringtoneDialogVisible = true, ringtoneError = null) }
        loadDeviceRingtones()
    }

    fun closeRingtoneDialog() {
        if (_uiState.value.ringtoneBusy) return
        _uiState.update { it.copy(ringtoneDialogVisible = false, ringtoneError = null) }
    }

    fun refreshDeviceRingtones() {
        loadDeviceRingtones()
    }

    private fun loadDeviceRingtones() {
        viewModelScope.launch {
            _uiState.update { it.copy(ringtoneBusy = true, ringtoneError = null) }

            val files = when (val filesResult = deviceRepository.listMp3Files()) {
                is ApiResult.Ok -> filesResult.data
                is ApiResult.Err -> {
                    _uiState.update {
                        it.copy(ringtoneBusy = false, ringtoneError = "读取设备音频失败：${filesResult.message}")
                    }
                    return@launch
                }
            }
            val globalRing = when (val voiceResult = deviceRepository.deviceAlarmVoice()) {
                is ApiResult.Ok -> voiceResult.data
                is ApiResult.Err -> null
            }
            _uiState.update {
                it.copy(
                    ringtoneBusy = false,
                    deviceRingtoneFiles = files,
                    currentDeviceRingtone = globalRing,
                )
            }
        }
    }

    /**
     * 选中本条闹钟的铃声；`null` = 跟随全局默认铃声。
     * 只改本地选择，保存时随 `add`/`upsert` 一起下发（`ring` 字段）。
     */
    fun selectRing(fileName: String?) {
        val normalized = fileName?.takeIf { it.isNotBlank() }
        _uiState.update { it.copy(selectedRingFile = normalized) }
        _message.value = if (normalized == null) {
            "已选择：跟随全局铃声"
        } else {
            "已选择铃声：$normalized（保存后生效）"
        }
    }

    /**
     * 从系统文件选择器选中的音频上传到路由器；成功后
     * ① 立刻刷新铃声候选列表 ② 自动选中服务端返回的实际文件名 ③ 提示保存后生效。
     */
    fun uploadRingtoneForDevice(uriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(ringtoneBusy = true, ringtoneError = null) }
            when (val result = deviceRepository.uploadRingtoneFromUri(uriString)) {
                is ApiResult.Ok -> {
                    val uploaded = result.data
                    // 自动选中刚上传的文件；随后刷新候选列表（含服务端可能加的 _1 序号）
                    _uiState.update {
                        it.copy(ringtoneBusy = false, selectedRingFile = uploaded.fileName)
                    }
                    _message.value = "已上传 ${uploaded.fileName}（${uploaded.sizeBytes} 字节），保存后本条闹钟生效"
                    loadDeviceRingtones()
                }

                is ApiResult.Err -> {
                    // 原样显示服务端 err / 本地校验的原文
                    _uiState.update {
                        it.copy(ringtoneBusy = false, ringtoneError = result.message)
                    }
                    _message.value = "上传失败：${result.message}"
                }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun consumeSaved() {
        _saved.value = false
    }

    fun save() {
        if (_uiState.value.saving) return
        viewModelScope.launch {
            val state = _uiState.value
            val repeat = when (state.mode) {
                RepeatUiMode.DAILY -> RepeatMode.Daily
                RepeatUiMode.WEEKLY -> {
                    if (state.weekdays.isEmpty()) {
                        _message.value = "请至少选择一个星期"
                        return@launch
                    }
                    RepeatMode.Weekly(state.weekdays.toSet())
                }

                RepeatUiMode.ONCE -> RepeatMode.Once(state.onceDate)
            }

            if (state.mode == RepeatUiMode.ONCE) {
                val preview = Alarm(
                    id = "date-check",
                    hour = state.hour,
                    minute = state.minute,
                    label = "",
                    enabled = true,
                    repeat = repeat,
                    ringtoneUri = null,
                    createdAt = 0L,
                )
                if (AlarmTime.nextTriggerMillis(preview) == null) {
                    _message.value = "这个日期时间已经过去，请重新选择"
                    return@launch
                }
            }

            _uiState.update { it.copy(saving = true) }
            val previousId = editingId
            // 编辑时要拿旧值：upsert 的 match 用「旧名称 + 旧时 + 旧分」
            val previous = if (previousId != null) repository.getAlarm(previousId) else null
            val alarm = Alarm(
                id = previousId ?: UUID.randomUUID().toString(),
                hour = state.hour,
                minute = state.minute,
                label = state.label.trim(),
                enabled = state.enabled,
                repeat = repeat,
                // 旧字段仅兼容旧数据；铃声现在是每条闹钟的 ringFile（服务端 rv/ring）
                ringtoneUri = null,
                createdAt = createdAt,
                // 本条闹钟独立的铃声（null = 跟随全局默认）
                ringFile = state.selectedRingFile,
            )

            // 新增 = 只调 dev/add；编辑 = 原子 dev/upsert（绝不 del+add，也绝不 clear）
            val result = if (previous != null) {
                deviceRepository.upsertAlarm(previous = previous, current = alarm)
            } else {
                deviceRepository.addAlarm(alarm)
            }
            _uiState.update { it.copy(saving = false) }

            if (result is ApiResult.Err) {
                // 设备是唯一真源，同步失败就视为未保存：留在本页提示，再点保存即可重试
                _message.value = "保存失败（设备未更新）：${result.message}"
                return@launch
            }
            if (previous == null) {
                // 新增成功后关闭编辑器；镜像已由 repository 从 /api/dev/list 刷新
                editingId = null
            }
            _saved.value = true
        }
    }

    companion object {
        fun factory(container: AppContainer, alarmId: String?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AlarmEditViewModel(
                        repository = container.alarmRepository,
                        deviceRepository = container.deviceRepository,
                        alarmId = alarmId,
                    )
                }
            }
    }
}
