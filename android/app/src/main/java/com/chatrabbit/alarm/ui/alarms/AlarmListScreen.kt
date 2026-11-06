package com.chatrabbit.alarm.ui.alarms

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chatrabbit.alarm.alarm.AlarmTime
import com.chatrabbit.alarm.data.Alarm
import com.chatrabbit.alarm.data.AppContainer
import com.chatrabbit.alarm.ui.components.AlarmRingPickerDialog
import com.chatrabbit.alarm.ui.components.DeviceSelector
import com.chatrabbit.alarm.ui.util.displayLabel

/**
 * 闹钟列表页：显示时间、名称、重复规则、开关；可编辑 / 删除 / 手动同步。
 * **纯设备闹钟**：列表是路由器闹钟表的镜像（进入页面 / 操作后都会 `GET /api/dev/list` 刷新），
 * 到点由玩具自己响，手机不响铃。
 */
@Composable
fun AlarmListScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onEditAlarm: (Alarm) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: AlarmListViewModel = viewModel(factory = AlarmListViewModel.factory(container))
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val ringDialog by viewModel.ringDialog.collectAsStateWithLifecycle()
    // 多设备（v1.10.0）：只有 1 台时切换器不渲染
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val currentDeviceId by viewModel.currentDeviceId.collectAsStateWithLifecycle()
    val defaultDeviceId by viewModel.defaultDeviceId.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Alarm?>(null) }
    var clearDeviceDialogVisible by remember { mutableStateOf(false) }

    // 每次进入闹钟列表页都从路由器整表刷新一次镜像
    LaunchedEffect(Unit) {
        viewModel.refreshFromDevice()
        viewModel.loadDevices()
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Column(modifier = modifier.fillMaxSize()) {
        DeviceSelector(
            devices = devices,
            currentDeviceId = currentDeviceId,
            defaultDeviceId = defaultDeviceId,
            onSelect = viewModel::switchDevice,
        )

        ListHeader(
            count = alarms.size,
            syncing = syncing,
            onRefresh = viewModel::refreshFromDeviceByUser,
            onSync = viewModel::syncNow,
            onClearDevice = { clearDeviceDialogVisible = true },
        )

        if (alarms.isEmpty()) {
            EmptyHint(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = alarms, key = { alarm -> alarm.id }) { alarm ->
                    AlarmRow(
                        alarm = alarm,
                        onToggle = { enabled -> viewModel.setEnabled(alarm, enabled) },
                        onClick = { onEditAlarm(alarm) },
                        onRingClick = { viewModel.openRingDialog(alarm) },
                        onDelete = { pendingDelete = alarm },
                    )
                }
            }
        }
    }

    val alarmToDelete = pendingDelete
    if (alarmToDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除闹钟") },
            text = {
                Text("确定删除「${alarmToDelete.displayLabel()}」吗？设备上的对应闹钟也会被同步移除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(alarmToDelete)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (clearDeviceDialogVisible) {
        AlertDialog(
            onDismissRequest = { clearDeviceDialogVisible = false },
            title = { Text("清空设备闹钟") },
            text = {
                Text(
                    "将删除设备上**全部**闹钟（调用 /api/dev/clear），手机端列表也会同步清空。\n" +
                        "此操作不可撤销，确定继续？",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearDeviceDialogVisible = false
                        viewModel.clearDeviceAlarms()
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { clearDeviceDialogVisible = false }) { Text("取消") }
            },
        )
    }

    // 快速改铃声：点列表里某条闹钟的「铃声：xxx」
    val ringTarget = ringDialog.alarm
    if (ringTarget != null) {
        AlarmRingPickerDialog(
            files = ringDialog.files,
            globalRing = ringDialog.globalRing,
            selectedRing = ringTarget.ringFile,
            busy = ringDialog.busy,
            error = ringDialog.error,
            onSelect = viewModel::selectRing,
            onUpload = { },
            onRefresh = viewModel::refreshRingFiles,
            onDismiss = viewModel::dismissRingDialog,
            showUpload = false,
        )
    }
}

@Composable
private fun ListHeader(
    count: Int,
    syncing: Boolean,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onClearDevice: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "共 $count 个闹钟",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (syncing) {
            Text(
                text = "同步中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "更多操作",
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("刷新（从设备读取）") },
                    enabled = !syncing,
                    onClick = {
                        menuExpanded = false
                        onRefresh()
                    },
                )
                DropdownMenuItem(
                    text = { Text("同步到设备（重新下发）") },
                    enabled = !syncing,
                    onClick = {
                        menuExpanded = false
                        onSync()
                    },
                )
                DropdownMenuItem(
                    text = { Text("清空设备闹钟") },
                    enabled = !syncing,
                    onClick = {
                        menuExpanded = false
                        onClearDevice()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "还没有闹钟",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "点右下角「新建闹钟」创建第一个",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AlarmRow(
    alarm: Alarm,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onRingClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = AlarmTime.timeText(alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (alarm.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
                Text(
                    text = alarm.displayLabel(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = AlarmTime.repeatText(alarm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 点这里可以只改铃声（不重排、不动时间）
                Text(
                    text = "铃声：${alarm.ringFile ?: "默认"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onRingClick)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            Switch(checked = alarm.enabled, onCheckedChange = onToggle)

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除闹钟",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
