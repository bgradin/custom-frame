package com.bgradin.customframe.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bgradin.customframe.data.DropboxRepository
import com.bgradin.customframe.data.Prefs

class PhotoSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (prefs.dropboxToken.isBlank()) return Result.success()
        return try {
            val repo = DropboxRepository(applicationContext, prefs.dropboxToken)
            val all = repo.listAllPhotosRecursively(prefs.dropboxFolder.ifBlank { "/" })
            repo.chooseAndDownloadBatch(all, 2.0, 200)
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}