package com.bgradin.customframe.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bgradin.customframe.data.DropboxRepository
import com.bgradin.customframe.data.Prefs
import com.bgradin.customframe.util.SyncLock

class PhotoSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val TAG = "PhotoSyncWorker";

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (prefs.dropboxToken.isBlank()) return Result.success()

        val ran = SyncLock.tryWithLock(applicationContext) {
            try {
                Log.i(TAG, "Starting Dropbox sync...");
                Log.i(TAG, "Detecting files in ${prefs.dropboxFolder}")

                val repo = DropboxRepository(applicationContext, prefs.dropboxToken)
                val all = repo.listAllPhotosRecursively(prefs.dropboxFolder.ifBlank { "/" })
                Log.i(TAG, "Found ${all.size} files");

                Log.i(TAG, "Choosing 200 photos and downloading...")
                repo.chooseAndDownloadBatch(all, 2.0, 200)

                Log.i(TAG, "Done!")
                Result.success()
            } catch (t: Throwable) {
                Log.e(TAG, "An error occurred during Dropbox sync", t);
                Result.retry()
            } finally {
                prefs.lastSyncAt = System.currentTimeMillis()
            }
        }

        return Result.success()
    }

    companion object {
        const val WORK_TAG = "photo_sync_tag"
        const val UNIQUE_NAME = "photo_sync_unique"
        const val NOW_WORK_NAME = "photo_sync_now"
    }
}