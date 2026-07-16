package com.kiodl.android

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KioskDownloaderApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setJobSchedulerJobIdRange(WORK_MANAGER_JOB_ID_MIN, WORK_MANAGER_JOB_ID_MAX)
            .build()

    private companion object {
        const val WORK_MANAGER_JOB_ID_MIN = 0x70000000
        const val WORK_MANAGER_JOB_ID_MAX = 0x7000ffff
    }
}

