package com.lightwing.filmbox.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 校准向导（D4 对照模式）：
 * 对着灰卡/均匀场景，把参考测光表（或机身内测光）给出的 EV 填进来，
 * 反算本机偏移。没参考器材时也可以跳过，默认偏移为 0。
 */
@Composable
fun CalibrationDialog(
    currentEv: Double?,
    currentOffset: Double,
    onApply: (referenceEv: Double) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("校准") },
        text = {
            Column {
                Text(
                    "对着 18% 灰卡或均匀受光的墙面，让读数稳定后，把参考测光表给出的 EV 填进来。\n" +
                        "没有测光表时，也可以用你胶片机身的内测光读数。",
                    fontSize = 13.sp, color = Color(0xFF8A8F98),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "当前读数 EV ${currentEv?.let { String.format("%.2f", it) } ?: "—"}",
                    fontSize = 15.sp,
                )
                Text(
                    "当前偏移 ${if (kotlin.math.abs(currentOffset) < 0.005) "无" else String.format("%+.2f", -currentOffset)} 档",
                    fontSize = 13.sp, color = Color(0xFF8A8F98),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { s -> input = s.filter { it.isDigit() || it == '.' || it == '-' } },
                    label = { Text("参考 EV（如 12.0）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = onReset) { Text("清除校准") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    input.toDoubleOrNull()?.let(onApply)
                },
                enabled = input.toDoubleOrNull() != null,
            ) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
