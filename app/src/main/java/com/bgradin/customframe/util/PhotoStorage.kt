package com.bgradin.customframe.util

import java.io.File
import java.io.IOException

object PhotoStorage {
    const val AVERAGE_IMAGE_BYTES = 5_000_000L
    const val RESERVE_BYTES = 100_000_000L
    const val MAX_BATCH = 200

    fun automaticLimit(availableBytes: Long): Int =
        ((availableBytes.coerceAtLeast(0) - RESERVE_BYTES).coerceAtLeast(0) /
            AVERAGE_IMAGE_BYTES).coerceAtMost(MAX_BATCH.toLong()).toInt()

    fun canDownload(availableBytes: Long, imageBytes: Long): Boolean =
        imageBytes >= 0 && availableBytes >= RESERVE_BYTES &&
            imageBytes <= availableBytes - RESERVE_BYTES

    // Called only after every selected download succeeds. The sync lock protects .part files.
    fun cleanup(directory: File, keep: Set<String>) {
        val files = directory.listFiles() ?: throw IOException("Cannot list photo storage")
        for (file in files) {
            if (file.isFile && file.name !in keep && !file.delete()) {
                throw IOException("Cannot remove outdated photo: ${file.name}")
            }
        }
    }
}
