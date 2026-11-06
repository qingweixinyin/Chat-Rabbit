package com.chatrabbit.alarm.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chatrabbit.alarm.BuildConfig
import com.chatrabbit.alarm.data.AppContainer
import com.chatrabbit.alarm.data.DeviceInfo
import com.chatrabbit.alarm.data.isLegacyPort
import com.chatrabbit.alarm.settings.SettingsRepository

/** 设置页：路由器地址 + 连接测试 + 关于（本 App 的闹钟是纯设备闹钟）。 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val savedUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val currentDeviceId by viewModel.currentDeviceId.collectAsStateWithLifecycle()
    val defaultDeviceId by viewModel.defaultDeviceId.collectAsStateWithLifecycle()
    val devicesLoading by viewModel.devicesLoading.collectAsStateWithLifecycle()

    var urlInput by remember(savedUrl) { mutableStateOf(savedUrl) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RouterCard(
            urlInput = urlInput,
            savedUrl = savedUrl,
            onUrlChange = { urlInput = it },
            testing = testing,
            onSave = { viewModel.saveBaseUrl(urlInput) },
            onSaveAndTest = { viewModel.saveAndTest(urlInput) },
        )

        DeviceManageCard(
            devices = devices,
            currentDeviceId = currentDeviceId,
            defaultDeviceId = defaultDeviceId,
            loading = devicesLoading,
            onRefresh = viewModel::loadDevices,
            onSelect = viewModel::switchDevice,
            onUseDefault = viewModel::useDefaultDevice,
        )

        AlarmModeCard()

        AboutCard()
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun RouterCard(
    urlInput: String,
    savedUrl: String,
    onUrlChange: (String) -> Unit,
    testing: Boolean,
    onSave: () -> Unit,
    onSaveAndTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "路由器地址", style = MaterialTheme.typography.titleMedium)

            // 当前生效的完整地址
            Text(
                text = "当前生效：$savedUrl",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (isLegacyPort(savedUrl)) {
                Text(
                    text = "当前是旧端口 8898，建议把服务端升级到 v1.6.0（新端口 8890）" +
                        "——装了 kongke 插件的路由器上 8898 可能被占用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("IP 或完整地址") },
                placeholder = { Text(SettingsRepository.DEFAULT_BASE_URL) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            )

            Text(
                text = "可只填 IP（如 192.168.3.1，自动补 http:// 和 :8890），也可填完整地址。" +
                    "默认 ${SettingsRepository.DEFAULT_BASE_URL}（家用路由器上跑的自建 mock 云端服务）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSave, enabled = !testing) { Text("直接保存") }
                Button(onClick = onSaveAndTest, enabled = !testing) {
                    Text(if (testing) "验证中…" else "验证并保存")
                }
            }

            Text(
                text = "「验证并保存」会先请求一次 GET /api/status：成功才写入；" +
                    "失败会提示超时 / HTTP 404 / 非 JSON 等具体原因，地址不会被改掉。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (testing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 说明：本 App 的闹钟是纯设备闹钟，手机不做本地响铃。 */
@Composable
private fun AlarmModeCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "闹钟行为", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "本 App 的闹钟只在玩具设备响：手机不会响铃、不会发通知、也不会亮屏。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "① 保存闹钟时 App 把它同步到路由器，路由器下发给玩具；" +
                    "② 玩具按自己的闹钟表响铃，铃声用「设备铃声」（编辑页里选）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text(
                text = "要让玩具响铃：确认设备页显示在线、闹钟已同步（列表页 ⋮ → 同步到设备），" +
                    "并且设备端铃声已选择（编辑闹钟时点「选择设备铃声」）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 设备管理（v1.10.0）：列出路由器登记的全部设备，可刷新 / 切换当前设备。 */
@Composable
private fun DeviceManageCard(
    devices: List<DeviceInfo>,
    currentDeviceId: String?,
    defaultDeviceId: String?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSelect: (DeviceInfo) -> Unit,
    onUseDefault: () -> Unit,
) {
    val effectiveId = currentDeviceId ?: defaultDeviceId
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "设备管理", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "一个 App 可控制多台玩具。切换后闹钟列表、铃声、设备状态都会跟着切到该设备。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (devices.isEmpty()) {
                Text(
                    text = "暂时没有设备列表（旧版服务端或路由器离线）。当前使用服务端默认设备。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                devices.forEach { device ->
                    val selected = device.id == effectiveId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (selected) {
                                    Modifier
                                } else {
                                    Modifier.clickable { onSelect(device) }
                                },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (selected) "✓ ${device.name}" else device.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = buildString {
                                    append(if (device.online) "在线" else "离线")
                                    append(" · 闹钟 ${device.alarmCount} 条")
                                    device.ip?.let { append(" · $it") }
                                    if (selected) append(" · 当前使用")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (currentDeviceId != null) {
                    OutlinedButton(onClick = onUseDefault) { Text("恢复使用服务端默认设备") }
                }
            }

            OutlinedButton(onClick = onRefresh, enabled = !loading) {
                Text(if (loading) "刷新中…" else "刷新列表")
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "关于", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "聊天兔闹钟 v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider()
            Text(
                text = "本机只负责配置与同步：闹钟、铃声、点歌都作用在路由器与玩具上；" +
                    "手机端不调度本地闹钟、不播放闹钟铃声。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "如果换了 WiFi / 路由器，到设备页「自动检测」或「修改地址」更新服务地址即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
