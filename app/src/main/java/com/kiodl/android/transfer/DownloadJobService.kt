package com.kiodl.android.transfer

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.annotation.RequiresApi
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(34)
class DownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val stoppedJobs = ConcurrentHashMap.newKeySet<Int>()
    private val dependencies by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            DownloadTransferDependencies::class.java,
        )
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val collectionId = params.extras.getString(COLLECTION_ID_KEY) ?: return false
        setNotification(
            params,
            dependencies.downloadNotificationFactory().notificationId(params.jobId),
            dependencies.downloadNotificationFactory().createPreparing(),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        jobs[params.jobId] = scope.launch {
            try {
                dependencies.downloadRunner().run(collectionId, params.network) { collection, transferredBytes ->
                    setNotification(
                        params,
                        dependencies.downloadNotificationFactory().notificationId(collection.schedulerId),
                        dependencies.downloadNotificationFactory().create(collection, transferredBytes),
                        JOB_END_NOTIFICATION_POLICY_REMOVE,
                    )
                    updateTransferredNetworkBytes(params, transferredBytes, 0)
                }
                if (!stoppedJobs.remove(params.jobId)) jobFinished(params, false)
            } catch (error: CancellationException) {
                withContext(NonCancellable) { dependencies.downloadRunner().recover(collectionId) }
            } catch (error: Exception) {
                dependencies.downloadRunner().recover(collectionId)
                if (!stoppedJobs.remove(params.jobId)) {
                    dependencies.downloadRunner().markError(collectionId, error)
                    jobFinished(params, false)
                }
            } finally {
                dependencies.downloadNotificationFactory().clearProgress(collectionId)
                jobs.remove(params.jobId)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stoppedJobs += params.jobId
        jobs.remove(params.jobId)?.cancel()
        return params.stopReason != JobParameters.STOP_REASON_USER &&
            params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val COLLECTION_ID_KEY = "collection_id"
    }
}
