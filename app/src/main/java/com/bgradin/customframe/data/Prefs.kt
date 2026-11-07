package com.bgradin.customframe.data

import android.content.Context
import androidx.core.content.edit

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var wifiSsid: String
        get() = sp.getString("wifiSsid", "") ?: ""
        set(v) = sp.edit { putString("wifiSsid", v) }

    var wifiPassword: String
        get() = sp.getString("wifiPassword", "") ?: ""
        set(v) = sp.edit { putString("wifiPassword", v) }

    var dropboxToken: String
        get() = sp.getString("dropboxToken", "") ?: ""
        set(v) = sp.edit { putString("dropboxToken", v) }

    var dropboxFolder: String
        get() = sp.getString("dropboxFolder", "/") ?: "/"
        set(v) = sp.edit { putString("dropboxFolder", v) }

    // Zero means automatic; recomputed from available storage at each sync.
    var photoSyncLimit: Int
        get() = sp.getInt("photoSyncLimit", 0).coerceIn(0, 200)
        set(v) = sp.edit { putInt("photoSyncLimit", v.coerceIn(0, 200)) }

    var showBlurredBackground: Boolean
        get() = sp.getBoolean("showBlurredBackground", true)
        set(v) = sp.edit { putBoolean("showBlurredBackground", v) }

    var lastSyncAt: Long
        get() = sp.getLong("lastSyncAt", 0L)
        set(v) = sp.edit { putLong("lastSyncAt", v).apply() }
    var wakeWindowEnabled: Boolean
        get() = sp.getBoolean("wakeWindowEnabled", true)
        set(v) = sp.edit { putBoolean("wakeWindowEnabled", v) }

    var wakeStartMinutes: Int
        get() = sp.getInt("wakeStartMinutes", 8 * 60)
        set(v) = sp.edit { putInt("wakeStartMinutes", v) }

    var wakeEndMinutes: Int
        get() = sp.getInt("wakeEndMinutes", 22 * 60)
        set(v) = sp.edit { putInt("wakeEndMinutes", v) }
}