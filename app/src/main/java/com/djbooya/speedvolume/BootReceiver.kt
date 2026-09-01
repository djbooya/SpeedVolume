package com.djbooya.speedvolume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        try {
            DebugLog.init(context)
            DebugLog.d("BootReceiver", "Boot broadcast received: ${intent.action}")

            val action = intent.action
            if (action != Intent.ACTION_BOOT_COMPLETED &&
                action != "android.intent.action.QUICKBOOT_POWERON" &&
                action != "com.htc.intent.action.QUICKBOOT_POWERON"
            ) {
                DebugLog.d("BootReceiver", "Unknown boot action: $action")
                return
            }

            val settings = SettingsRepository(context).load()
            DebugLog.d("BootReceiver", "Settings: enabled=${settings.masterEnabled}, startOnBoot=${settings.startOnBoot}")

            if (!settings.masterEnabled || !settings.startOnBoot) {
                DebugLog.d("BootReceiver", "Service start skipped - master disabled or startOnBoot disabled")
                return
            }

            DebugLog.d("BootReceiver", "Starting service on boot")
            val serviceIntent = Intent(context, SpeedVolumeService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
            DebugLog.d("BootReceiver", "startForegroundService called")
        } finally {
            pendingResult.finish()
        }
    }
}
