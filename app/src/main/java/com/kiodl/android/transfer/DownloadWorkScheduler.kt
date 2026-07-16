package com.kiodl.android.transfer

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class DownloadWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(
        collectionId: String,
        schedulerId: Int,
        estimatedDownloadBytes: Long,
    ) {
        // Prefer user-initiated data transfer (API 34+) for longer foreground-like runs; WM is fallback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val scheduled = uidtScheduler().schedule(
                JobInfo.Builder(
                    schedulerId,
                    ComponentName(context, DownloadJobService::class.java),
                )
                    .setExtras(
                        PersistableBundle().apply {
                            putString(DownloadJobService.COLLECTION_ID_KEY, collectionId)
                        },
                    )
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setEstimatedNetworkBytes(
                        estimatedDownloadBytes.coerceAtLeast(0),
                        JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
                    )
                    .setUserInitiated(true)
                    .setPersisted(true)
                    .build(),
            )
            if (scheduled == JobScheduler.RESULT_SUCCESS) return
        }
        enqueueWorkManager(collectionId)
    }

    suspend fun cancel(collectionId: String, schedulerId: Int) {
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(collectionId)).result.get()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            uidtScheduler().cancel(schedulerId)
        }
    }

    private fun enqueueWorkManager(collectionId: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.COLLECTION_ID_KEY to collectionId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        // KEEP avoids restarting an already-running unique work when resume re-enqueues.
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(collectionId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun workName(collectionId: String) = "download-$collectionId"

    @RequiresApi(34)
    private fun uidtScheduler() = context.getSystemService(JobScheduler::class.java)
        .forNamespace("download-uidt")
}
