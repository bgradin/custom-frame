package com.bgradin.customframe

import com.bgradin.customframe.diagnostics.AppLog

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bgradin.customframe.work.PhotoSyncWorker
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLog.event("memory_trim", "level=$level ${AppLog.memory()}")
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.initialize(this)
        // Reserve headroom for navigation on the frame's small heap.
        com.bumptech.glide.Glide.init(this, com.bumptech.glide.GlideBuilder()
            .setMemoryCache(com.bumptech.glide.load.engine.cache.LruResourceCache(4L * 1024 * 1024))
            .setBitmapPool(com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool(4L * 1024 * 1024)))

        // Observe physical display transitions independently of activity recreation.
        androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                AppLog.event("screen", "action=${intent.action}")
                when (intent.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> com.bgradin.customframe.power.FramePower.onScreenOff(context)
                    android.content.Intent.ACTION_SCREEN_ON -> {
                        com.bgradin.customframe.power.FramePower.onForeground(context)
                        com.bgradin.customframe.power.FramePower.apply(context)
                    }
                }
            }
        }, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)

        // WorkManager schedules hourly sync when connectivity is available.
        val req = PeriodicWorkRequestBuilder<PhotoSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .addTag(PhotoSyncWorker.WORK_TAG)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PhotoSyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }
}