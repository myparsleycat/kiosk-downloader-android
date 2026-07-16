package com.kiodl.android.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.kiodl.android.R
import com.kiodl.android.data.local.DownloadEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadNotificationFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val speedSamples = ConcurrentHashMap<String, SpeedSample>()

    fun create(
        collection: DownloadEntity,
        transferredBytes: Long,
        cancelIntent: PendingIntent? = null,
    ): Notification {
        createChannel()
        val max = collection.totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val progress = if (collection.totalBytes <= 0) {
            0
        } else {
            (transferredBytes.toDouble() / collection.totalBytes * max).toInt().coerceIn(0, max)
        }
        val percent = if (collection.totalBytes > 0) {
            (transferredBytes.toDouble() / collection.totalBytes * 100).toInt().coerceIn(0, 100)
        } else {
            null
        }
        val speedBps = sampleSpeed(collection.id, transferredBytes, System.currentTimeMillis())
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(collection.name)
            .setContentText(buildProgressText("다운로드 중", percent, speedBps))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, progress, collection.totalBytes <= 0)
            .apply {
                if (cancelIntent != null) addAction(0, "취소", cancelIntent)
            }
            .build()
    }

    fun createPreparing(): Notification {
        createChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("다운로드 준비 중")
            .setContentText("전송을 시작하고 있습니다.")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    fun createUpload(name: String, transferredBytes: Long, totalBytes: Long, uploadId: String): Notification {
        createChannel()
        val max = totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val progress = if (totalBytes <= 0) 0
        else (transferredBytes.toDouble() / totalBytes * max).toInt().coerceIn(0, max)
        val percent = if (totalBytes > 0) {
            (transferredBytes.toDouble() / totalBytes * 100).toInt().coerceIn(0, 100)
        } else {
            null
        }
        val speedBps = sampleSpeed("upload:$uploadId", transferredBytes, System.currentTimeMillis())
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(name)
            .setContentText(buildProgressText("업로드 중", percent, speedBps))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, progress, totalBytes <= 0)
            .build()
    }

    fun clearProgress(key: String) {
        speedSamples.remove(key)
    }

    fun notificationId(schedulerId: Int) = schedulerId

    private fun createChannel() {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "다운로드", NotificationManager.IMPORTANCE_LOW),
        )
    }

    // Progress callbacks arrive at irregular checkpoints (not fixed intervals). Time-constant EMA
    // (alpha = 1 - e^(-dt/τ)) keeps displayed speed stable without biasing short/long gaps.
    private fun sampleSpeed(key: String, transferredBytes: Long, now: Long): Long {
        val sample = speedSamples.computeIfAbsent(key) { SpeedSample() }
        if (sample.lastTime == 0L) {
            sample.lastBytes = transferredBytes
            sample.lastTime = now
            sample.emaBps = 0L
            return 0L
        }
        val elapsed = now - sample.lastTime
        val speedBps = if (elapsed > 0) {
            val delta = (transferredBytes - sample.lastBytes).coerceAtLeast(0)
            val instantBps = delta * 1_000 / elapsed
            val alpha = 1.0 - Math.exp(-elapsed.toDouble() / EMA_TAU_MS)
            (sample.emaBps * (1.0 - alpha) + instantBps * alpha).toLong().coerceAtLeast(0)
        } else {
            sample.emaBps
        }
        sample.lastBytes = transferredBytes
        sample.lastTime = now
        sample.emaBps = speedBps
        return speedBps
    }

    private fun buildProgressText(label: String, percent: Int?, speedBps: Long): String =
        buildList {
            add(label)
            percent?.let { add("$it%") }
            if (speedBps > 0) add("${formatBytes(speedBps)}/s")
        }.joinToString(" · ")

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex += 1
        }
        return "%.1f %s".format(value, units[unitIndex])
    }

    private data class SpeedSample(
        var lastBytes: Long = 0L,
        var lastTime: Long = 0L,
        var emaBps: Long = 0L,
    )

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val EMA_TAU_MS = 1_500.0
    }
}