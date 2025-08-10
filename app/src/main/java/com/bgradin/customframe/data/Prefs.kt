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

    var slideIntervalMs: Long
        get() = sp.getLong("slideIntervalMs", 7000)
        set(v) = sp.edit { putLong("slideIntervalMs", v) }
}