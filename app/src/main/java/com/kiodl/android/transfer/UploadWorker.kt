package com.kiodl.android.transfer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

class UploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val dependencies = EntryPointAccessors.fromApplication(context, UploadTransferDependencies::class.java)

    override suspend fun doWork(): Result {
        val id = inputData.getString(UPLOAD_ID_KEY) ?: return Result.failure()
        val schedulerId = inputData.getInt(UPLOAD_SCHEDULER_ID_KEY, 0).takeIf { it > 0 }
            ?: return Result.failure()
        setForeground(createForegroundInfo(schedulerId, 0, 0, id))
        return try {
            dependencies.uploadRunner().run(id, onProgress = { done, total ->
                setForeground(createForegroundInfo(schedulerId, done, total, id))
            })
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.failure()
        } finally {
            dependencies.notificationFactory().clearProgress("upload:$id")
        }
    }

    private fun createForegroundInfo(schedulerId: Int, done: Long, total: Long, uploadId: String): ForegroundInfo = ForegroundInfo(
        schedulerId,
        dependencies.notificationFactory().createUpload("파일 업로드", done, total, uploadId),
    )

    companion object {
        const val UPLOAD_ID_KEY = "upload_id"
        const val UPLOAD_SCHEDULER_ID_KEY = "upload_scheduler_id"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UploadTransferDependencies {
    fun uploadRunner(): UploadRunner
    fun notificationFactory(): DownloadNotificationFactory
}
