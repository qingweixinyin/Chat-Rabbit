package com.chatrabbit.alarm.ui.device

import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chatrabbit.alarm.data.AppContainer
import com.chatrabbit.alarm.data.RING_SECONDS_OPTIONS
import com.chatrabbit.alarm.ui.components.DeviceSelector
import com.chatrabbit.alarm.ui.components.RouterAddressDialog

/**
 * 设备页（聊天兔固件实测可用功能）：
 * 1) 连接信息（GET /api/status：device_online / clients / device_ip / last_connect）+ 地址修改 / 自动检测
 * 2) 响铃测试（POST /api/ringtest）
 * 3) 响铃时长（POST /api/ring {"seconds":10|20|30|60}，协议允许 1~120）
 * 4) 设备铃声显示 + 重新上传（POST /api/ringtone/upload?name=xxx）
 *
 * 固件已裁掉音量/灯光控制，故不再提供相关按钮。
 */
@Composable
fun DeviceScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: DeviceViewModel = viewModel(factory = DeviceViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 待确认删除的文件名（点垃圾桶后弹确认框）
    var pendingDeleteName by remember { mutableStateOf<String?>(null) }

    // 选择本地音频 -> 上传替换设备铃声
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
            viewModel.uploadRingtone(uri.toString())
        }
    }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeviceSelector(
            devices = state.devices,
            currentDeviceId = state.currentDeviceId,
            defaultDeviceId = state.defaultDeviceId,
            onSelect = viewModel::switchDevice,
        )

        ConnectionCard(
            state = state,
            onEditAddress = viewModel::openAddressDialog,
            onAutoDetect = viewModel::autoDetect,
            onRefresh = viewModel::refresh,
        )

        if (state.online == false) {
            OfflineHintCard(
                state = state,
                onEditAddress = viewModel::openAddressDialog,
                onAutoDetect = viewModel::autoDetect,
            )
        }

        RingInfoCard(state = state)
        MusicCard(
            state = state,
            onPlay = viewModel::playMusic,
            onSetDefault = viewModel::setDefaultMusic,
            onStop = viewModel::stopMusic,
            onDelete = { fileName -> pendingDeleteName = fileName },
        )
        RingTestCard(state = state, onTest = viewModel::ringTest)
        RingSecondsCard(state = state, onSelect = viewModel::selectRingSeconds)
        RingtoneUploadCard(
            state = state,
            onPick = { ringtonePicker.launch(arrayOf("audio/*")) },
        )

        Text(
            text = "设备固件不支持远程音量 / 灯光控制",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (state.addressDialogVisible) {
        RouterAddressDialog(
            input = state.addressInput,
            validating = state.addressValidating,
            errorMessage = state.addressError,
            onInputChange = viewModel::setAddressInput,
            onConfirm = viewModel::confirmAddress,
            onDismiss = viewModel::dismissAddressDialog,
        )
    }

    val deleteTarget = pendingDeleteName
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteName = null },
            title = { Text("删除音频文件") },
            text = {
                Text(
                    "删除 $deleteTarget？\n删除后不可恢复；" +
                        "若它是当前铃声或默认音乐，服务端会把对应设置置空。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteName = null
                        viewModel.deleteMediaFile(deleteTarget)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteName = null }) { Text("取消") }
            },
        )
    }
}

// ==================== 连接信息 ====================

@Composable
private fun ConnectionCard(
    state: DeviceUiState,
    onEditAddress: () -> Unit,
    onAutoDetect: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "连接信息", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                if (state.busy || state.autoDetecting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            // 路由器地址：可点击直接编辑
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onEditAddress)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "路由器地址",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = state.baseUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "修改",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                val (statusText, dotColor) = when (state.online) {
                    true -> "在线" to MaterialTheme.colorScheme.primary
                    false -> "离线" to MaterialTheme.colorScheme.error
                    null -> "未检测" to MaterialTheme.colorScheme.outline
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "连接状态：$statusText", style = MaterialTheme.typography.bodyMedium)
            }

            InfoRow(label = "已连接客户端", value = state.clients.toString())
            InfoRow(
                label = "设备 IP（路由器记录）",
                value = state.deviceIp ?: "—",
            )
            InfoRow(label = "最近连接", value = state.lastConnect ?: "—")

            if (state.autoDetecting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = state.autoDetectProgress ?: "正在自动检测…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onAutoDetect,
                    enabled = !state.autoDetecting && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.autoDetecting) "检测中…" else "自动检测")
                }
                OutlinedButton(
                    onClick = onEditAddress,
                    enabled = !state.autoDetecting,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("修改地址")
                }
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !state.busy && !state.autoDetecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("重新检测")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OfflineHintCard(
    state: DeviceUiState,
    onEditAddress: () -> Unit,
    onAutoDetect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "设备离线（路由器未收到玩具连接）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = "· 玩具是否开机、连的是同一个 WiFi？",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = "· 当前路由器地址：${state.baseUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = "· 路由器返回的最近设备 IP：${state.deviceIp ?: "未知"}（可能是旧记录）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEditAddress) { Text("修改地址") }
                TextButton(onClick = onAutoDetect) { Text("自动检测") }
            }
        }
    }
}

// ==================== 铃声信息 / 响铃测试 / 时长 / 上传 ====================

@Composable
private fun RingInfoCard(state: DeviceUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "设备铃声信息", style = MaterialTheme.typography.titleMedium)
            InfoRow(
                label = "当前铃声",
                value = state.alarmVoice ?: "未设置（默认铃声）",
            )
            InfoRow(label = "设备响铃时长", value = "${state.deviceRingSeconds} 秒")
        }
    }
}

@Composable
private fun RingTestCard(state: DeviceUiState, onTest: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "响铃测试", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "让玩具立刻播放它当前的铃声，用来确认设备端铃声是否设置成功。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Button(
                onClick = onTest,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("让设备播放铃声") }
            Text(
                text = "对应 POST /api/ringtest",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RingSecondsCard(state: DeviceUiState, onSelect: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "响铃时长", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "设备当前：${state.deviceRingSeconds} 秒",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RING_SECONDS_OPTIONS.forEach { seconds ->
                    val selected = state.selectedRingSeconds == seconds
                    val buttonModifier = Modifier.weight(1f)
                    val contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    if (selected) {
                        Button(
                            onClick = { onSelect(seconds) },
                            enabled = !state.busy,
                            modifier = buttonModifier,
                            contentPadding = contentPadding,
                        ) { Text("${seconds}s") }
                    } else {
                        OutlinedButton(
                            onClick = { onSelect(seconds) },
                            enabled = !state.busy,
                            modifier = buttonModifier,
                            contentPadding = contentPadding,
                        ) { Text("${seconds}s") }
                    }
                }
            }

            Text(
                text = "点一下即下发 POST /api/ring，body 为 {\"seconds\":N}；协议允许 1~120 秒。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RingtoneUploadCard(
    state: DeviceUiState,
    onPick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "上传设备铃声", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "设备当前铃声：${state.alarmVoice ?: "未设置（使用设备默认铃声）"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onPick,
                enabled = !state.busy && state.deletingName == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "上传中…" else "选择音频并上传到路由器") }

            Text(
                text = "从系统文件选择器挑一个 MP3，App 把原始字节 POST 到 " +
                    "/api/ringtone/upload?name=文件名，路由器保存到 media 目录（和网页上传是同一个目录）。" +
                    "上传后可到上面的「音乐 / 点歌」卡片里播放或删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "上传成功后会自动设为全局铃声；每条闹钟自己的铃声请在「编辑闹钟 → 本条闹钟的铃声」里选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ==================== 音乐 / 点歌 ====================

/** media 目录列表最多展示多少条（避免长列表把页面撑爆） */
private const val MAX_VISIBLE_FILES = 12

/**
 * 音乐 / 点歌卡片：显示 `music_file`，列出 media 目录里的音频，
 * 每行可 **播放 / 设为默认 / 删除**（删除带二次确认，见 DeviceScreen）。
 */
@Composable
private fun MusicCard(
    state: DeviceUiState,
    onPlay: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onStop: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "音乐 / 点歌", style = MaterialTheme.typography.titleMedium)
            InfoRow(
                label = "当前默认音乐",
                value = state.musicFile ?: "未设置",
            )

            if (state.mediaFiles.isEmpty()) {
                Text(
                    text = "media 目录里还没有音频文件，先在上面「上传设备铃声」里选一个上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "media 文件（${state.mediaFiles.size}）",
                    style = MaterialTheme.typography.labelLarge,
                )
                state.mediaFiles.take(MAX_VISIBLE_FILES).forEach { fileName ->
                    MusicFileRow(
                        fileName = fileName,
                        isDefault = fileName == state.musicFile,
                        busy = state.musicActionFile == fileName,
                        enabled = state.musicActionFile == null && state.deletingName == null,
                        onPlay = { onPlay(fileName) },
                        onSetDefault = { onSetDefault(fileName) },
                        onDelete = { onDelete(fileName) },
                    )
                }
                if (state.mediaFiles.size > MAX_VISIBLE_FILES) {
                    Text(
                        text = "只显示前 $MAX_VISIBLE_FILES 个，共 ${state.mediaFiles.size} 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(
                onClick = onStop,
                enabled = !state.musicStopping,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.musicStopping) "停止中…" else "停止播放") }

            Text(
                text = "播放 POST /api/play，停止 POST /api/stop，设为默认 POST /api/music；" +
                    "删除走 POST /api/file/delete（删掉默认音乐时服务端会把 music_file 置空）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 音乐文件行：文件名（可省略）+ 播放 / 设为默认 / 删除。
 * 播放或设默认时显示行内进度，期间禁用其它行的按钮；删除同理（[deletingName]）。
 */
@Composable
private fun MusicFileRow(
    fileName: String,
    isDefault: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isDefault) {
                Text(
                    text = "默认音乐",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onPlay, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放 $fileName",
                )
            }
            IconButton(onClick = onSetDefault, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "设为默认音乐 $fileName",
                    tint = if (isDefault) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除 $fileName",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
