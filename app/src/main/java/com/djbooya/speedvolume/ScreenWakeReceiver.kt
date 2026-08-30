package com.djbooya.speedvolume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ScreenWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_ON) {
            DebugLog.init(context)
            DebugLog.d("ScreenWakeReceiver", "=== SCREEN_ON: Device woke from sleep ===")
            android.util.Log.d("SpeedVolume", "=== SCREEN_ON DETECTED ===")

            val settings = SettingsRepository(context).load()
            if (!settings.masterEnabled) {
                DebugLog.d("ScreenWakeReceiver", "Service disabled by user, not restarting")
                return
            }

            val serviceIntent = Intent(context, SpeedVolumeService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
            DebugLog.d("ScreenWakeReceiver", "Service restart initiated on screen wake")
            android.util.Log.d("SpeedVolume", "Service restart initiated on device wake")
        }
    }
}
