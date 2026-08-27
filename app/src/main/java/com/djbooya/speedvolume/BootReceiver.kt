package com.djbooya.speedvolume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        val settings = SettingsRepository(context).load()
        if (!settings.masterEnabled || !settings.startOnBoot) return

        val serviceIntent = Intent(context, SpeedVolumeService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
