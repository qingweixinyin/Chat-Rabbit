package com.chatrabbit.alarm.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chatrabbit.alarm.data.DeviceInfo

/**
 * 多设备切换下拉（v1.10.0）。
 *
 * 只有 1 台设备（或列表拉取失败）时**不渲染**，旧单设备体验完全不受影响。
 * 显示格式：`设备名（在线/离线）`，当前选中的打勾标记。
 */
@Composable
fun DeviceSelector(
    devices: List<DeviceInfo>,
    currentDeviceId: String?,
    defaultDeviceId: String?,
    onSelect: (DeviceInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (devices.size <= 1) return

    var expanded by remember { mutableStateOf(false) }
    val effectiveId = currentDeviceId ?: defaultDeviceId
    val current = devices.firstOrNull { it.id == effectiveId }
        ?: devices.firstOrNull { it.id == defaultDeviceId }
        ?: devices.first()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "当前设备",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) { }
        OutlinedButton(onClick = { expanded = true }) {
            Text(
                text = "${current.name}（${if (current.online) "在线" else "离线"}）",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            devices.forEach { device ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (device.id == effectiveId) "✓ ${device.name}" else device.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = buildString {
                                    append(if (device.online) "在线" else "离线")
                                    append(" · 闹钟 ${device.alarmCount} 条")
                                    device.ip?.let { append(" · $it") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(device)
                    },
                )
            }
        }
    }
}
