package com.chatrabbit.alarm.ui.provision

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chatrabbit.alarm.ble.BleDevice
import com.chatrabbit.alarm.ble.BlePermissions
import com.chatrabbit.alarm.ble.ProvisionState
import com.chatrabbit.alarm.data.AppContainer
import kotlinx.coroutines.launch

private const val MAX_VISIBLE_DEVICES = 6
private const val LOG_PREVIEW_LINES = 12

/**
 * BLE 配网页：扫描 → 选设备 → 填 WiFi → 开始配网 → 进度日志 → 结果。
 * 协议实现见 [com.chatrabbit.alarm.ble.BleProvisioner] / [com.chatrabbit.alarm.ble.LeNetCfgProtocol]。
 */
@Composable
fun ProvisionScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: ProvisionViewModel = viewModel(factory = ProvisionViewModel.factory(container))
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    var permissionsGranted by remember { mutableStateOf(BlePermissions.granted(context)) }
    var bluetoothEnabled by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var autoScanStarted by remember { mutableStateOf(false) }
    val bluetoothSupported = remember { viewModel.isBluetoothSupported() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsGranted = BlePermissions.granted(context)
        bluetoothEnabled = viewModel.isBluetoothEnabled()
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        bluetoothEnabled = viewModel.isBluetoothEnabled()
    }

    // 首次进入时立刻读一次权限 / 蓝牙状态（ON_RESUME 只在之后触发）
    LaunchedEffect(Unit) {
        permissionsGranted = BlePermissions.granted(context)
        bluetoothEnabled = viewModel.isBluetoothEnabled()
    }

    // 从系统设置返回时刷新权限 / 蓝牙开关状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsGranted = BlePermissions.granted(context)
                bluetoothEnabled = viewModel.isBluetoothEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopScan() }
    }

    LaunchedEffect(permissionsGranted, bluetoothEnabled, bluetoothSupported) {
        if (!autoScanStarted && bluetoothSupported && permissionsGranted && bluetoothEnabled) {
            autoScanStarted = true
            viewModel.startScan()
        }
    }

    val running = state is ProvisionState.Running
    val selectedDevice = devices.firstOrNull { it.address == ui.selectedAddress }
    val canStart = permissionsGranted && bluetoothEnabled && !running &&
        selectedDevice != null && ui.ssid.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            !bluetoothSupported -> InfoCard(
                title = "此设备不支持蓝牙",
                detail = "给玩具配网需要支持 BLE 的手机。你可以先用其它手机完成配网，本机仍可正常控制闹钟与设备。",
            )

            !permissionsGranted -> PermissionCard(
                onRequest = { permissionLauncher.launch(BlePermissions.required()) },
                onOpenSettings = { BlePermissions.openAppPermissionSettings(context) },
            )

            !bluetoothEnabled -> BluetoothOffCard(
                onEnable = {
                    runCatching {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }
                },
            )
        }

        DeviceSection(
            devices = devices,
            scanning = state is ProvisionState.Scanning,
            selectedAddress = ui.selectedAddress,
            scanEnabled = permissionsGranted && bluetoothEnabled && !running,
            onScan = { viewModel.startScan() },
            onSelect = { viewModel.selectDevice(it) },
        )

        WifiSection(
            ssid = ui.ssid,
            password = ui.password,
            ssidPrefilled = ui.ssidPrefilled,
            passwordVisible = passwordVisible,
            enabled = !running,
            onSsidChange = viewModel::setSsid,
            onPasswordChange = viewModel::setPassword,
            onTogglePassword = { passwordVisible = !passwordVisible },
        )

        WifiCompatCard(ssid = ui.ssid, enabled = !running)

        RunSection(
            state = state,
            logs = logs,
            onlineMessage = ui.onlineMessage,
            canStart = canStart,
            onStart = { viewModel.startProvision() },
            onCancel = { viewModel.cancelProvision() },
        )

        HelpCard()
        Spacer(modifier = Modifier.height(24.dp))
    }

    // 轮询结束（成功或超时）后用 snackbar 再提示一次；结果卡片里也会一直显示
    LaunchedEffect(ui.pollingOnline, ui.onlineMessage) {
        if (!ui.pollingOnline) {
            val message = ui.onlineMessage ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(message)
        }
    }
}

// ==================== 卡片 ====================

@Composable
private fun PermissionCard(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "需要蓝牙权限",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = "Android 12+ 需要「附近的设备」权限（扫描 + 连接）；" +
                    "Android 11 及以下需要定位权限才能扫描 BLE 设备。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequest) { Text("授予权限") }
                OutlinedButton(onClick = onOpenSettings) { Text("打开应用设置") }
            }
        }
    }
}

@Composable
private fun BluetoothOffCard(onEnable: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "蓝牙未开启",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = "配网需要通过 BLE 把 WiFi 名称和密码发给玩具。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Button(onClick = onEnable) { Text("打开蓝牙") }
        }
    }
}

@Composable
private fun InfoCard(title: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceSection(
    devices: List<BleDevice>,
    scanning: Boolean,
    selectedAddress: String?,
    scanEnabled: Boolean,
    onScan: () -> Unit,
    onSelect: (BleDevice) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "① 选择设备", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onScan, enabled = scanEnabled) {
                    Text(if (scanning) "扫描中…" else "重新扫描")
                }
            }

            if (scanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (devices.isEmpty()) {
                Text(
                    text = if (scanning) {
                        "正在搜索附近设备…"
                    } else {
                        "还没有扫描到设备。点「重新扫描」；若一直搜不到，请查看底部帮助。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                devices.take(MAX_VISIBLE_DEVICES).forEach { device ->
                    DeviceRow(
                        device = device,
                        selected = device.address == selectedAddress,
                        onClick = { onSelect(device) },
                    )
                }
                if (devices.size > MAX_VISIBLE_DEVICES) {
                    Text(
                        text = "只显示前 $MAX_VISIBLE_DEVICES 个，共 ${devices.size} 个设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "带「配网服务」标记的设备，广播里包含 0xFFFF 服务，置顶显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceRow(device: BleDevice, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (device.hasConfigService) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "配网服务",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${device.rssi} dBm",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "已选",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun WifiSection(
    ssid: String,
    password: String,
    ssidPrefilled: Boolean,
    passwordVisible: Boolean,
    enabled: Boolean,
    onSsidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "② 输入 WiFi 信息", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "玩具只支持 2.4GHz WiFi；请填家里路由器的 SSID 和密码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = ssid,
                onValueChange = onSsidChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
                label = { Text("WiFi 名称（SSID）") },
                supportingText = {
                    Text(if (ssidPrefilled) "已自动填入当前手机连接的 WiFi，可修改" else "手机未授权定位时无法自动读取，请手动输入")
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
            )

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
                label = { Text("WiFi 密码") },
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    TextButton(onClick = onTogglePassword, enabled = enabled) {
                        Text(
                            text = if (passwordVisible) "隐藏" else "显示",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun RunSection(
    state: ProvisionState,
    logs: List<String>,
    onlineMessage: String?,
    canStart: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val running = state is ProvisionState.Running
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "③ 开始配网", style = MaterialTheme.typography.titleMedium)

            if (state is ProvisionState.Running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "当前步骤：${state.step.label}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onStart,
                    enabled = canStart,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (running) "配网中…" else "开始配网")
                }
                if (running) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                }
            }

            if (logs.isNotEmpty()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "进度日志", style = MaterialTheme.typography.labelLarge)
                    val context = LocalContext.current
                    TextButton(
                        onClick = {
                            runCatching {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                                cm?.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "chatrabbit-provision-log",
                                        logs.joinToString("\n"),
                                    ),
                                )
                            }
                        },
                    ) {
                        Text("复制日志")
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    logs.takeLast(LOG_PREVIEW_LINES).forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when (state) {
                is ProvisionState.Succeeded -> ResultBanner(
                    success = true,
                    title = "配网成功",
                    detail = onlineMessage ?: "设备正在连接 WiFi…",
                )

                is ProvisionState.Failed -> ResultBanner(
                    success = false,
                    title = "配网失败",
                    detail = state.reason,
                )

                else -> Unit
            }
        }
    }
}

@Composable
private fun ResultBanner(success: Boolean, title: String, detail: String) {
    val containerColor = if (success) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (success) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = contentColor)
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = contentColor)
        }
    }
}

@Composable
private fun WifiCompatCard(ssid: String, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<WifiCompatReport?>(null) }
    var checking by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "②½ WiFi 兼容自检（只读，不改任何网络）", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "玩具只支持：2.4GHz / WPA2-PSK(AES) / 信道 1–11。点一下就能看出这个 SSID 是否符合，" +
                    "不符合时会直接告诉你该改什么。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    if (checking) return@Button
                    if (!wifiScanPermissionGranted(context)) {
                        val perms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.NEARBY_WIFI_DEVICES,
                            )
                        } else {
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        permissionLauncher.launch(perms)
                        return@Button
                    }
                    checking = true
                    scope.launch {
                        report = checkWifiCompat(context, ssid)
                        checking = false
                    }
                },
                enabled = enabled && ssid.isNotBlank() && !checking,
            ) {
                Text(if (checking) "检测中…" else "自检该 SSID")
            }

            report?.let { r ->
                if (r.found) {
                    Text(
                        text = "找到「${r.exactSsid}」：${r.bandLabel} / 信道 ${r.channel ?: "?"} / " +
                            "${r.securityLabel}" + (r.widthMhz?.let { " / ${it}MHz" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (r.ok) {
                    Text(
                        text = "✅ 参数看起来没问题，可以直接配网",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    r.issues.forEach { issue ->
                        Text(
                            text = "⚠ $issue",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (!wifiScanPermissionGranted(context)) {
                    Text(
                        text = "提示：没有定位/附近设备权限时扫不到 WiFi 列表，授权后再点一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "搜索不到设备？", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "让玩具进入配网模式：断电重启。若仍然搜索不到，说明它还记得旧网络，" +
                    "需要先清除 WiFi 配置（长按复位键，或换到新网络后重启）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "配网过程：手机把 SSID / 密码分片写入 FF01，玩具收齐后自己连 WiFi，" +
                    "结果通过 FF02 通知返回；成功后本页会自动轮询 /api/status 确认设备上线。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
