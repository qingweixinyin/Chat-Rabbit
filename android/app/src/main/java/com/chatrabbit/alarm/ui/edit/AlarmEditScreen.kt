package com.chatrabbit.alarm.ui.edit

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chatrabbit.alarm.alarm.AlarmTime
import com.chatrabbit.alarm.data.AppContainer
import com.chatrabbit.alarm.ui.components.AlarmRingPickerDialog
import com.chatrabbit.alarm.ui.util.rememberScopedViewModelStoreOwner
import java.time.LocalDate

/**
 * 新建 / 编辑闹钟页。
 * 时间选择用系统 TimePickerDialog、日期用系统 DatePickerDialog（无实验性 API）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    container: AppContainer,
    alarmId: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val storeOwner = rememberScopedViewModelStoreOwner()
    val viewModel: AlarmEditViewModel = viewModel(
        viewModelStoreOwner = storeOwner,
        factory = AlarmEditViewModel.factory(container, alarmId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler { onClose() }

    LaunchedEffect(saved) {
        if (saved) {
            viewModel.consumeSaved()
            onClose()
        }
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    // 铃声选择：ACTION_OPEN_DOCUMENT + 持久化读权限
    val ringtonePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.uploadRingtoneForDevice(uri.toString())
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (alarmId == null) "新建闹钟" else "编辑闹钟") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !state.saving) {
                        Text("保存")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            TimeCard(
                hour = state.hour,
                minute = state.minute,
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> viewModel.setTime(hour, minute) },
                        state.hour,
                        state.minute,
                        true,
                    ).show()
                },
            )

            OutlinedTextField(
                value = state.label,
                onValueChange = viewModel::setLabel,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("闹钟名称") },
                placeholder = { Text("例如：起床") },
                singleLine = true,
            )

            RepeatSection(
                state = state,
                onModeChange = viewModel::setMode,
                onToggleWeekday = viewModel::toggleWeekday,
                onPickDate = {
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            viewModel.setOnceDate(LocalDate.of(year, month + 1, dayOfMonth))
                        },
                        state.onceDate.year,
                        state.onceDate.monthValue - 1,
                        state.onceDate.dayOfMonth,
                    ).show()
                },
            )

            DeviceRingtoneCard(
                selectedRing = state.selectedRingFile,
                globalRing = state.currentDeviceRingtone,
                onOpenPicker = viewModel::openRingtoneDialog,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "启用（仅影响玩具设备）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "关闭后设备端这条闹钟不生效；手机不会响铃",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                }
            }

            HorizontalDivider()

            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(if (state.saving) "保存中…" else "保存闹钟")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (state.ringtoneDialogVisible) {
        AlarmRingPickerDialog(
            files = state.deviceRingtoneFiles,
            globalRing = state.currentDeviceRingtone,
            selectedRing = state.selectedRingFile,
            busy = state.ringtoneBusy,
            error = state.ringtoneError,
            onSelect = viewModel::selectRing,
            onUpload = { ringtonePicker.launch(arrayOf("audio/mpeg")) },
            onRefresh = viewModel::refreshDeviceRingtones,
            onDismiss = viewModel::closeRingtoneDialog,
        )
    }
}

@Composable
private fun TimeCard(hour: Int, minute: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "闹钟时间",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = AlarmTime.timeText(hour, minute),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = "修改",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RepeatSection(
    state: AlarmEditUiState,
    onModeChange: (RepeatUiMode) -> Unit,
    onToggleWeekday: (Int) -> Unit,
    onPickDate: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "重复", style = MaterialTheme.typography.titleSmall)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeButton(
                    text = "每天",
                    selected = state.mode == RepeatUiMode.DAILY,
                    onClick = { onModeChange(RepeatUiMode.DAILY) },
                    modifier = Modifier.weight(1f),
                )
                ModeButton(
                    text = "指定星期",
                    selected = state.mode == RepeatUiMode.WEEKLY,
                    onClick = { onModeChange(RepeatUiMode.WEEKLY) },
                    modifier = Modifier.weight(1f),
                )
                ModeButton(
                    text = "仅一次",
                    selected = state.mode == RepeatUiMode.ONCE,
                    onClick = { onModeChange(RepeatUiMode.ONCE) },
                    modifier = Modifier.weight(1f),
                )
            }

            when (state.mode) {
                RepeatUiMode.DAILY -> Text(
                    text = "每天同一时间响铃",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                RepeatUiMode.WEEKLY -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WeekdaySelector(selected = state.weekdays, onToggle = onToggleWeekday)
                    Text(
                        text = AlarmTime.weekdayText(state.weekdays),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                RepeatUiMode.ONCE -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onPickDate)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "日期", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = state.onceDate.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = "一次性闹钟触发后会自动关闭（设备端同样按一次性处理）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    if (selected) {
        Button(onClick = onClick, modifier = modifier, contentPadding = contentPadding) {
            Text(text = text, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = contentPadding) {
            Text(text = text, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun WeekdaySelector(selected: Set<Int>, onToggle: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (day in 1..7) {
            val isSelected = day in selected
            val background = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
            val contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .clickable { onToggle(day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = AlarmTime.weekdayName(day).removePrefix("周"),
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun DeviceRingtoneCard(
    selectedRing: String?,
    globalRing: String?,
    onOpenPicker: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "本条闹钟的铃声", style = MaterialTheme.typography.titleSmall)
            Text(
                text = selectedRing ?: "跟随全局铃声（${globalRing ?: "未设置"}）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenPicker) { Text("选择本条铃声") }
            Text(
                text = "铃声是给**玩具**播的，手机不播放。默认「跟随全局铃声」；" +
                    "也可以给这条闹钟单独选一个 media 目录里的 MP3（含内置铃声音），" +
                    "或点「上传新音频到路由器」上传到路由器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}