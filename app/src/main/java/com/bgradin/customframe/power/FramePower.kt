package com.bgradin.customframe.power

import com.bgradin.customframe.diagnostics.AppLog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.bgradin.customframe.data.Prefs
import com.bgradin.customframe.util.WakeWindow
import java.util.Calendar
import org.json.JSONObject

/** Owns the schedule even while the activity and CPU are asleep. */
object FramePower {
    private const val NIX = "com.kitesystems.nix.frame"
    private const val SLEEP = "$NIX.powermanagement.SleepReceiver"
    private const val WAKE = "$NIX.powermanagement.WakeupReceiver"
    private const val ACTION = "com.bgradin.customframe.POWER_BOUNDARY"
    var lastRemoteWakeAt = Long.MIN_VALUE
        private set

    private fun state(context: Context) = context.getSharedPreferences("framePower", Context.MODE_PRIVATE)
    private fun interactive(context: Context) = context.getSystemService(PowerManager::class.java).isInteractive

    private data class PowerProfile(val version: Long, val disabled: Set<String>)

    private fun profiles(context: Context): List<PowerProfile> =
        context.assets.list("power-profiles").orEmpty().filter { it.endsWith(".json") }.map { name ->
            val json = context.assets.open("power-profiles/$name").bufferedReader().use { JSONObject(it.readText()) }
            require(json.getString("Package") == NIX && json.getString("Protocol") == "nixplay-broadcast-v1")
            fun names(key: String): List<String> = json.getJSONArray(key).let { array ->
                (0 until array.length()).map { array.getString(it) }
            }
            val power = names("PowerReceivers")
            val disabled = names("DisabledComponents")
            require(power.size == 2 && power.toSet() == setOf(SLEEP, WAKE))
            require(disabled.isNotEmpty() && disabled.size == disabled.toSet().size && disabled.none { it in power })
            PowerProfile(json.getLong("VersionCode"), disabled.toSet())
        }

    /** Require a contributed profile plus verified isolation of installed components. */
    @Suppress("DEPRECATION")
    fun hardwareAvailable(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(NIX, PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or
                PackageManager.MATCH_DISABLED_COMPONENTS)
            val profile = profiles(context).singleOrNull { it.version == info.versionCode.toLong() }
                ?: return false
            fun enabled(name: String, manifestEnabled: Boolean): Boolean = when (
                pm.getComponentEnabledSetting(ComponentName(NIX, name))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabled
                else -> false
            }
            val activities = info.activities.orEmpty()
            val receivers = info.receivers.orEmpty()
            val services = info.services.orEmpty()
            val alarms = context.getSystemService(AlarmManager::class.java)
            val installed = (activities.map { it.name } + receivers.map { it.name } + services.map { it.name }).toSet()
            profile.disabled.all { it in installed && !enabled(it, true) } &&
                info.applicationInfo?.enabled == true &&
                (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) &&
                activities.isNotEmpty() && activities.all { !enabled(it.name, it.enabled) } &&
                receivers.filter { it.name == SLEEP || it.name == WAKE }.let { power ->
                    power.size == 2 && power.all { enabled(it.name, it.enabled) }
                } && receivers.filter { it.name != SLEEP && it.name != WAKE }
                    .all { !enabled(it.name, it.enabled) } && services.filter {
                        it.name.startsWith("com.kitesystems.") || it.name.startsWith("com.nixplay.") ||
                            it.name.startsWith("com.softwinner.")
                    }.all { !enabled(it.name, it.enabled) }
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (e: Exception) {
            AppLog.event("power_profile_error", AppLog.failure(e))
            Log.w("FramePower", "Cannot load power profiles; using display blanking", e)
            false
        }
    }

    fun isSleeping(context: Context, now: Calendar = Calendar.getInstance()): Boolean {
        val state = state(context)
        if (state.getLong("overrideUntil", 0L) > now.timeInMillis) {
            return state.getBoolean("overrideSleeping", false)
        }
        val prefs = Prefs(context)
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return prefs.wakeWindowEnabled && !WakeWindow.isAwake(minute, prefs.wakeStartMinutes, prefs.wakeEndMinutes)
    }

    fun reset(context: Context) {
        AppLog.event("power_override_reset")
        state(context).edit { remove("overrideUntil") }
    }

    private fun override(context: Context, sleeping: Boolean) {
        val prefs = Prefs(context)
        val end = if (sleeping) prefs.wakeStartMinutes else prefs.wakeEndMinutes
        state(context).edit(commit = true) {
            putLong("overrideUntil", WakeWindow.nextEnd(Calendar.getInstance(), end))
            putBoolean("overrideSleeping", sleeping)
        }
    }

    fun toggle(context: Context) {
        AppLog.event("remote_power", "sleeping=${!isSleeping(context)}")
        override(context, !isSleeping(context))
    }

    /** Firmware wakes on the remote's F1 key; grant a schedule override before evaluating sleep. */
    fun onForeground(context: Context) {
        if (state(context).getBoolean("hardwareSleeping", false) &&
            state(context).getBoolean("hardwareSleepObserved", false) && interactive(context)) {
            AppLog.event("remote_wake")
            override(context, false)
            state(context).edit(commit = true) { putBoolean("hardwareSleeping", false) }
            lastRemoteWakeAt = SystemClock.elapsedRealtime()
        }
    }

    fun onScreenOff(context: Context) {
        if (state(context).getBoolean("hardwareSleeping", false)) {
            state(context).edit(commit = true) { putBoolean("hardwareSleepObserved", true) }
        }
    }

    private fun nativePower(context: Context, sleeping: Boolean) {
        AppLog.event("hardware_power_request", "sleeping=$sleeping")
        val receiver = if (sleeping) "SleepReceiver" else "WakeupReceiver"
        context.sendBroadcast(Intent(if (sleeping) "com.kitesystems.nix.ACTION_SLEEP" else "com.kitesystems.nix.ACTION_WAKEUP")
            .setComponent(ComponentName(NIX, if (sleeping) SLEEP else WAKE))
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra(receiver, "CustomFrame")
            // Force physical sleep rather than Nixplay's clock activity.
            .putExtra("POWERBUTTON_PRESSED", true))
    }

    /** Arm the next boundary before requesting physical sleep. */
    fun apply(context: Context): Boolean {
        val now = Calendar.getInstance()
        val prefs = Prefs(context)
        val state = state(context)
        val overrideUntil = state.getLong("overrideUntil", 0L)
        val next = when {
            overrideUntil > now.timeInMillis -> overrideUntil
            prefs.wakeWindowEnabled && prefs.wakeStartMinutes != prefs.wakeEndMinutes -> minOf(
                WakeWindow.nextEnd(now, prefs.wakeStartMinutes), WakeWindow.nextEnd(now, prefs.wakeEndMinutes))
            else -> null
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val alarm = PendingIntent.getBroadcast(context, 0,
            Intent(context, FramePowerReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.cancel(alarm)
        var exactArmed = false
        if (next != null) {
            try {
                if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm)
                    exactArmed = true
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm)
                }
            } catch (e: SecurityException) {
                AppLog.event("power_alarm_error", AppLog.failure(e))
                Log.w("FramePower", "Exact wake alarm unavailable; using display blanking", e)
            }
        }
        val sleeping = isSleeping(context, now)
        val wasHardwareSleeping = state.getBoolean("hardwareSleeping", false)
        if (!sleeping && wasHardwareSleeping) {
            state.edit(commit = true) { putBoolean("hardwareSleeping", false) }
            nativePower(context, false)
        } else if (sleeping && exactArmed && !wasHardwareSleeping && hardwareAvailable(context)) {
            state.edit(commit = true) {
                putBoolean("hardwareSleeping", true)
                putBoolean("hardwareSleepObserved", false)
            }
            nativePower(context, true)
        }
        return sleeping
    }
}
