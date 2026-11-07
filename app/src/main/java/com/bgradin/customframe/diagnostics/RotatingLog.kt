package com.bgradin.customframe.diagnostics

import java.io.File

/** A bounded, process-local journal. Callers serialize appends with this monitor. */
class RotatingLog(private val directory: File, private val maxBytes: Int = 256 * 1024) {
    @Synchronized
    fun append(line: String) {
        directory.mkdirs()
        val current = File(directory, "events.log")
        val bytes = (line.take(maxBytes / 4) + "\n").toByteArray(Charsets.UTF_8)
        if (current.length() + bytes.size > maxBytes) {
            File(directory, "events.3.log").delete()
            for (index in 2 downTo 1) {
                val source = File(directory, "events.$index.log")
                if (source.exists()) check(source.renameTo(File(directory, "events.${index + 1}.log")))
            }
            if (current.exists()) check(current.renameTo(File(directory, "events.1.log")))
        }
        current.appendBytes(bytes)
    }
}
