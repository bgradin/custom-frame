package com.bgradin.customframe.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bgradin.customframe.data.FrameServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ServerRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        FrameServer(applicationContext).refresh()
        Result.success()
    }
}
