package com.djbooya.speedvolume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ConnectivityChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.net.conn.CONNECTIVITY_CHANGE") {
            val pendingResult = goAsync()
            try {
                DebugLog.init(context)
                DebugLog.d("ConnectivityChangeReceiver", "Network connectivity changed")
                android.util.Log.d("SpeedVolume", "=== CONNECTIVITY CHANGE DETECTED ===")

                val settings = SettingsRepository(context).load()
                if (!settings.masterEnabled) {
                    DebugLog.d("ConnectivityChangeReceiver", "Service disabled by user, not restarting")
                    return
                }

                val serviceIntent = Intent(context, SpeedVolumeService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
                DebugLog.d("ConnectivityChangeReceiver", "Service ensured running on connectivity change")
                android.util.Log.d("SpeedVolume", "Service restart on network change")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
