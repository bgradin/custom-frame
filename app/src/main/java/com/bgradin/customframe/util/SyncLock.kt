package com.bgradin.customframe.util

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException

object SyncLock {
    private const val LOCK_FILE = "photo_sync.lock"

    /** Returns true if lock was acquired and block executed; false if another sync is running. */
    fun tryWithLock(ctx: Context, block: () -> Unit): Boolean {
        val f = File(ctx.filesDir, LOCK_FILE)
        RandomAccessFile(f, "rw").channel.use { ch: FileChannel ->
            val lock = try { ch.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) return false
            return try {
                block()
                true
            } finally {
                lock.release()
            }
        }
    }
}