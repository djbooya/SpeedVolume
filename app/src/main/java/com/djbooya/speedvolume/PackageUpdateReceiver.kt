package com.djbooya.speedvolume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class PackageUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            try {
                DebugLog.init(context)
                DebugLog.d("PackageUpdateReceiver", "=== MY_PACKAGE_REPLACED: App updated/repaired ===")
                android.util.Log.d("SpeedVolume", "=== APP UPDATED/REPLACED ===")

                val settings = SettingsRepository(context).load()
                if (!settings.masterEnabled) {
                    DebugLog.d("PackageUpdateReceiver", "Service disabled by user, not restarting")
                    return
                }

                val serviceIntent = Intent(context, SpeedVolumeService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
                DebugLog.d("PackageUpdateReceiver", "Service restarted on package update")
                android.util.Log.d("SpeedVolume", "Service restart initiated on package update")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
