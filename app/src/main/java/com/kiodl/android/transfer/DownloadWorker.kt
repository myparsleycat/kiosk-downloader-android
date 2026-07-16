package com.kiodl.android.transfer

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class DownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    private val dependencies = EntryPointAccessors.fromApplication(
        appContext,
        DownloadTransferDependencies::class.java,
    )

    override suspend fun doWork(): Result {
        val collectionId = inputData.getString(COLLECTION_ID_KEY) ?: return Result.failure()
        return try {
            dependencies.downloadRunner().run(collectionId) { collection, transferredBytes ->
                val notification = dependencies.downloadNotificationFactory().create(
                    collection = collection,
                    transferredBytes = transferredBytes,
                )
                setForeground(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ForegroundInfo(
                            dependencies.downloadNotificationFactory().notificationId(collection.schedulerId),
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                        )
                    } else {
                        ForegroundInfo(
                            dependencies.downloadNotificationFactory().notificationId(collection.schedulerId),
                            notification,
                        )
                    },
                )
            }
            Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable) { dependencies.downloadRunner().recover(collectionId) }
            throw error
        } catch (error: Exception) {
            dependencies.downloadRunner().recover(collectionId)
            dependencies.downloadRunner().markError(collectionId, error)
            Result.failure(workDataOf("error" to (error.message ?: "Download failed.")))
        } finally {
            dependencies.downloadNotificationFactory().clearProgress(collectionId)
        }
    }

    companion object {
        const val COLLECTION_ID_KEY = "collection_id"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadTransferDependencies {
    fun downloadRunner(): DownloadRunner
    fun downloadNotificationFactory(): DownloadNotificationFactory
}
