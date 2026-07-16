package com.kiodl.android.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ConfirmRemoveDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("기록 삭제") },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = {
                onConfirm()
                onDismiss()
            }) { Text("삭제") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("취소") }
        },
    )
}