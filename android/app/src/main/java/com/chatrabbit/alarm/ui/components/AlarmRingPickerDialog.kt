package com.chatrabbit.alarm.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 一条闹钟的铃声选择器（编辑页与列表页快速改铃声共用）。
 *
 * - 第一项常驻「跟随全局铃声」（对应 `ring` 为空）；
 * - 下面是 media 目录里的 MP3（含路由器内置铃声音），当前选中的标出来；
 * - 「＋ 上传新音频到路由器」由调用方接管（SAF → 上传到路由器 → 刷新列表 → 自动选中）。
 *
 * 铃声只作用于**设备**（路由器按命中的闹钟选铃声播放），手机不播放。
 */
@Composable
fun AlarmRingPickerDialog(
    files: List<String>,
    globalRing: String?,
    selectedRing: String?,
    busy: Boolean,
    error: String?,
    onSelect: (String?) -> Unit,
    onUpload: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    showUpload: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("本条闹钟的铃声") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "全局默认铃声：${globalRing ?: "未设置"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "铃声只作用于设备（路由器按命中的闹钟选铃声播放），手机不播放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                if (showUpload) {
                    TextButton(onClick = onUpload, enabled = !busy) {
                        Text("＋ 上传新音频到路由器（MP3）")
                    }
                }

                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 第一项：跟随全局
                    RingOptionRow(
                        title = "跟随全局铃声",
                        subtitle = globalRing?.let { "使用 $it" } ?: "使用设备全局默认铃声",
                        selected = selectedRing == null,
                        enabled = !busy,
                        onClick = { onSelect(null) },
                    )

                    if (files.isEmpty() && !busy) {
                        Text(
                            text = "media 目录里还没有 MP3，先点上面「＋ 上传新音频到路由器」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }

                    files.forEach { fileName ->
                        RingOptionRow(
                            title = fileName,
                            subtitle = null,
                            selected = fileName == selectedRing,
                            enabled = !busy,
                            onClick = { onSelect(fileName) },
                        )
                    }
                }

                TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新列表") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("完成") }
        },
    )
}

@Composable
private fun RingOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Text(
                text = "使用中",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
