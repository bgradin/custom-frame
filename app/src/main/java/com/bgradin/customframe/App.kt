package com.bgradin.customframe

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bgradin.customframe.work.PhotoSyncWorker
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Kick off hourly sync (flex 15 min)
        val req = PeriodicWorkRequestBuilder<PhotoSyncWorker>(1, TimeUnit.HOURS)
            .addTag(PhotoSyncWorker.WORK_TAG)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PhotoSyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }
}