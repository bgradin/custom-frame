package com.bgradin.customframe.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FramePowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            FramePower.reset(context)
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                context.getSharedPreferences("framePower", Context.MODE_PRIVATE).edit()
                    .putBoolean("hardwareSleeping", false).commit()
            }
        }
        FramePower.apply(context)
    }
}
