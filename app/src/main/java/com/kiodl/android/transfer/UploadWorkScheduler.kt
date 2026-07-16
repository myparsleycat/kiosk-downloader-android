package com.kiodl.android.transfer

import android.content.Context
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class UploadWorkScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun enqueue(id: String, schedulerId: Int) {
        // Prefer user-initiated data transfer (API 34+); WorkManager is the pre-34 / schedule-fail fallback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val scheduled = uidtScheduler().schedule(
                JobInfo.Builder(
                    schedulerId,
                    ComponentName(context, UploadJobService::class.java),
                )
                    .setExtras(PersistableBundle().apply { putString(UploadJobService.UPLOAD_ID_KEY, id) })
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setEstimatedNetworkBytes(JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
                    .setUserInitiated(true)
                    .setPersisted(true)
                    .build(),
            )
            if (scheduled == JobScheduler.RESULT_SUCCESS) return
        }
        // REPLACE restarts work with fresh input (schedulerId) after pause/resume re-enqueue.
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id),
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    workDataOf(
                        UploadWorker.UPLOAD_ID_KEY to id,
                        UploadWorker.UPLOAD_SCHEDULER_ID_KEY to schedulerId,
                    ),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    suspend fun cancel(id: String, schedulerId: Int) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id)).result.get()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            uidtScheduler().cancel(schedulerId)
        }
    }

    private fun workName(id: String) = "upload-$id"

    @RequiresApi(34)
    private fun uidtScheduler() = context.getSystemService(JobScheduler::class.java).forNamespace("upload-uidt")
}
