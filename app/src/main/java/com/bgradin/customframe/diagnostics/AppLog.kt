package com.bgradin.customframe.diagnostics

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** No credentials, SSIDs, Dropbox paths, photo names, or exception messages belong here. */
object AppLog {
    private var journal: RotatingLog? = null
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(128), { task -> Thread(task, "app-log").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy())
    @Volatile private var foreground = false
    @Volatile private var heartbeat = SystemClock.uptimeMillis()

    fun initialize(app: Application) {
        if (journal != null) return
        journal = RotatingLog(File(app.filesDir, "diagnostics"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // Write synchronously: a queued entry may disappear when Android kills us.
                persist("crash", "thread=${thread.name} ${failure(error)}")
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
        event("process_start", "version=${app.packageManager.getPackageInfo(app.packageName, 0).versionName} " +
            "sdk=${android.os.Build.VERSION.SDK_INT} uptimeMs=${SystemClock.elapsedRealtime()} ${memory()}")
        val main = Handler(Looper.getMainLooper())
        main.post(object : Runnable {
            override fun run() {
                heartbeat = SystemClock.uptimeMillis()
                main.postDelayed(this, 1000)
            }
        })
        // Runs separately from both UI and log writer; records once per stall, then recovery.
        val power = app.getSystemService(PowerManager::class.java)
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ui-watchdog").apply { isDaemon = true }
        }.scheduleWithFixedDelay(object : Runnable {
            var stalled = false
            override fun run() {
                val lag = SystemClock.uptimeMillis() - heartbeat
                if (foreground && power.isInteractive && lag >= 5000) {
                    if (!stalled) {
                        stalled = true
                        event("ui_stall", "lagMs=$lag ${memory()}\n" +
                            Looper.getMainLooper().thread.stackTrace.take(80).joinToString("\n"))
                    }
                } else if (stalled) {
                    stalled = false
                    event("ui_stall_ended", "lagMs=$lag foreground=$foreground")
                }
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    fun foreground(active: Boolean) {
        foreground = active
        if (active) heartbeat = SystemClock.uptimeMillis()
    }

    fun event(kind: String, detail: String = "") {
        val entry = format(kind, detail)
        writer.execute { write(entry) }
    }

    fun failure(error: Throwable): String = errorDetails(error)

    fun memory(): String = Runtime.getRuntime().let {
        "heapUsed=${it.totalMemory() - it.freeMemory()} heapMax=${it.maxMemory()} nativeHeap=${android.os.Debug.getNativeHeapAllocatedSize()}"
    }

    private fun format(kind: String, detail: String) =
        "${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())} uptimeMs=${SystemClock.elapsedRealtime()} pid=${android.os.Process.myPid()} $kind $detail"

    private fun persist(kind: String, detail: String) = write(format(kind, detail))

    private fun write(entry: String) {
        try { journal?.append(entry) }
        catch (_: Exception) { Log.w("CustomFrameLog", "Unable to write diagnostic journal") }
    }
}
