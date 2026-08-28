package com.djbooya.speedvolume

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ServiceRestartAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RESTART_SERVICE) {
            DebugLog.init(context)
            DebugLog.d("ServiceRestartAlarm", "Alarm triggered - checking if service needs restart")

            val settings = SettingsRepository(context).load()
            if (!settings.masterEnabled) {
                DebugLog.d("ServiceRestartAlarm", "Service disabled by user, not restarting")
                return
            }

            val serviceIntent = Intent(context, SpeedVolumeService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
            DebugLog.d("ServiceRestartAlarm", "Service restart initiated")
        }
    }

    companion object {
        private const val ACTION_RESTART_SERVICE = "com.djbooya.speedvolume.ACTION_RESTART_SERVICE"
        private const val REQUEST_CODE = 1001
        private const val INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

        fun scheduleRestartAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ServiceRestartAlarm::class.java).apply {
                action = ACTION_RESTART_SERVICE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    INTERVAL_MS,
                    INTERVAL_MS,
                    pendingIntent
                )
                DebugLog.d("ServiceRestartAlarm", "Alarm scheduled - checks every 30 minutes")
            } catch (e: Exception) {
                DebugLog.e("ServiceRestartAlarm", "Failed to schedule alarm", e)
            }
        }

        fun cancelRestartAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ServiceRestartAlarm::class.java).apply {
                action = ACTION_RESTART_SERVICE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            DebugLog.d("ServiceRestartAlarm", "Alarm cancelled")
        }
    }
}
