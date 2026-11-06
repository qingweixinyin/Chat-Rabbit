package com.chatrabbit.alarm.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 「修改路由器地址」对话框。
 * 支持只填 IP（`192.168.3.1`），保存时会自动补 `http://` 和 `:8890`。
 */
@Composable
fun RouterAddressDialog(
    input: String,
    validating: Boolean,
    errorMessage: String?,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!validating) onDismiss() },
        title = { Text("修改路由器地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !validating,
                    label = { Text("IP 或完整地址") },
                    placeholder = { Text("192.168.3.1") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )
                Text(
                    text = "可只填 IP（自动补 http:// 和 :8890），也可填 http://192.168.3.1:8890",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (validating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !validating) {
                Text(if (validating) "验证中…" else "保存并验证")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !validating) {
                Text("取消")
            }
        },
    )
}
