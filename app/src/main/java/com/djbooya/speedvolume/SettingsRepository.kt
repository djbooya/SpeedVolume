package com.djbooya.speedvolume

import android.content.Context

class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        masterEnabled = prefs.getBoolean(KEY_MASTER_ENABLED, false),
        speedUnit = if (prefs.getString(KEY_UNIT, SpeedUnit.KMH.name) == SpeedUnit.MPH.name) SpeedUnit.MPH else SpeedUnit.KMH,
        startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, true),
        tier1 = TierConfig(
            enabled = prefs.getBoolean(KEY_T1_ENABLED, true),
            speedThreshold = prefs.getInt(KEY_T1_SPEED, 45),
            volumeIncreaseSteps = prefs.getInt(KEY_T1_INCREASE, 3),
            dwellSeconds = prefs.getInt(KEY_T1_DWELL, 5)
        ),
        tier2 = TierConfig(
            enabled = prefs.getBoolean(KEY_T2_ENABLED, false),
            speedThreshold = prefs.getInt(KEY_T2_SPEED, 90),
            volumeIncreaseSteps = prefs.getInt(KEY_T2_INCREASE, 4),
            dwellSeconds = prefs.getInt(KEY_T2_DWELL, 5)
        )
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_MASTER_ENABLED, settings.masterEnabled)
            .putString(KEY_UNIT, settings.speedUnit.name)
            .putBoolean(KEY_START_ON_BOOT, settings.startOnBoot)
            .putBoolean(KEY_T1_ENABLED, settings.tier1.enabled)
            .putInt(KEY_T1_SPEED, settings.tier1.speedThreshold)
            .putInt(KEY_T1_INCREASE, settings.tier1.volumeIncreaseSteps)
            .putInt(KEY_T1_DWELL, settings.tier1.dwellSeconds)
            .putBoolean(KEY_T2_ENABLED, settings.tier2.enabled)
            .putInt(KEY_T2_SPEED, settings.tier2.speedThreshold)
            .putInt(KEY_T2_INCREASE, settings.tier2.volumeIncreaseSteps)
            .putInt(KEY_T2_DWELL, settings.tier2.dwellSeconds)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "speed_volume_prefs"
        private const val KEY_MASTER_ENABLED = "master_enabled"
        private const val KEY_UNIT = "speed_unit"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_T1_ENABLED = "t1_enabled"
        private const val KEY_T1_SPEED = "t1_speed"
        private const val KEY_T1_INCREASE = "t1_increase"
        private const val KEY_T1_DWELL = "t1_dwell"
        private const val KEY_T2_ENABLED = "t2_enabled"
        private const val KEY_T2_SPEED = "t2_speed"
        private const val KEY_T2_INCREASE = "t2_increase"
        private const val KEY_T2_DWELL = "t2_dwell"
    }
}
