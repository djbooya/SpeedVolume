package com.djbooya.speedvolume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScreenReceiver : BroadcastReceiver() {
    companion object {
        private var serviceRef: SpeedVolumeService? = null

        fun setService(service: SpeedVolumeService) {
            serviceRef = service
        }

        fun clearService() {
            serviceRef = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_ON) {
            serviceRef?.onScreenOn()
        }
    }
}
