package com.bgradin.customframe.work

import com.bgradin.customframe.diagnostics.AppLog

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bgradin.customframe.data.DropboxRepository
import com.bgradin.customframe.data.Prefs
import com.bgradin.customframe.util.SyncLock
import com.bgradin.customframe.util.PhotoStorage

class PhotoSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val TAG = "PhotoSyncWorker";

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (prefs.dropboxToken.isBlank()) {
            AppLog.event("sync_skipped", "reason=no_token")
            return Result.success()
        }
        AppLog.event("sync_start", "id=$id attempt=$runAttemptCount")

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var result: Result = Result.retry()
            try {
                val acquired = SyncLock.tryWithLock(applicationContext) {
                    val repo = DropboxRepository(applicationContext, prefs.dropboxToken)
                    val all = repo.listAllPhotosRecursively(prefs.dropboxFolder)
                    val limit = prefs.photoSyncLimit.takeIf { it > 0 }
                        ?: PhotoStorage.automaticLimit(applicationContext.filesDir.usableSpace)
                    // Zero space must not turn a populated offline library into an empty batch.
                    if (limit == 0 && all.isNotEmpty()) throw java.io.IOException("Insufficient photo storage")
                    AppLog.event("sync_listing", "available=${all.size} limit=$limit")
                    repo.chooseAndDownloadBatch(all, 2.0, limit)
                    prefs.lastSyncAt = System.currentTimeMillis()
                    AppLog.event("sync_success", "id=$id")
                    result = Result.success()
                }
                if (!acquired) AppLog.event("sync_retry", "reason=lock_busy")
                result
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                AppLog.event("sync_cancelled", "id=$id")
                throw cancelled
            } catch (e: Exception) {
                AppLog.event("sync_retry", "id=$id ${AppLog.failure(e)}")
                Log.e(TAG, "Dropbox sync failed", e)
                Result.retry()
            }
        }
    }

    companion object {
        const val WORK_TAG = "photo_sync_tag"
        const val UNIQUE_NAME = "photo_sync_unique"
        const val NOW_WORK_NAME = "photo_sync_now"
    }
}