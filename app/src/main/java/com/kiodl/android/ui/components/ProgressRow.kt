package com.kiodl.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kiodl.android.ui.util.buildProgressText

@Composable
fun ProgressRow(
    progress: Float,
    done: Long,
    total: Long,
    speed: Long?,
    elapsed: Long?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            error ?: buildProgressText(done, total, speed, elapsed),
            style = MaterialTheme.typography.labelSmall,
            color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
        )
    }
}