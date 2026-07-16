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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@RequiresApi(34)
class UploadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val dependencies by lazy {
        EntryPointAccessors.fromApplication(applicationContext, UploadTransferDependencies::class.java)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString(UPLOAD_ID_KEY) ?: return false
        setNotification(
            params,
            params.jobId,
            dependencies.notificationFactory().createUpload("업로드 준비 중", 0, 0, id),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        jobs[params.jobId] = scope.launch {
            try {
                dependencies.uploadRunner().run(id, params.network) { done, total ->
                    setNotification(
                        params,
                        params.jobId,
                        dependencies.notificationFactory().createUpload("파일 업로드", done, total, id),
                        JOB_END_NOTIFICATION_POLICY_REMOVE,
                    )
                    updateTransferredNetworkBytes(params, 0, done)
                }
                jobFinished(params, false)
            } catch (_: CancellationException) {
                Unit
            } catch (_: Exception) {
                jobFinished(params, false)
            } finally {
                dependencies.notificationFactory().clearProgress("upload:$id")
                jobs.remove(params.jobId)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return params.stopReason != JobParameters.STOP_REASON_USER &&
            params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val UPLOAD_ID_KEY = "upload_id" }
}
