package com.djbooya.speedvolume

import android.content.Context

class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        masterEnabled = prefs.getBoolean(KEY_MASTER_ENABLED, false),
        speedUnit = if (prefs.getString(KEY_UNIT, SpeedUnit.KMH.name) == SpeedUnit.MPH.name) SpeedUnit.MPH else SpeedUnit.KMH,
        startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, true),
        tiers = List(AppSettings.TIER_COUNT) { index ->
            val default = TIER_DEFAULTS[index]
            TierConfig(
                enabled = prefs.getBoolean(keyEnabled(index), default.enabled),
                speedThreshold = prefs.getInt(keySpeed(index), default.speedThreshold),
                volumeIncreaseSteps = prefs.getInt(keyIncrease(index), default.volumeIncreaseSteps),
                dwellSeconds = prefs.getInt(keyDwell(index), default.dwellSeconds)
            )
        }
    )

    fun save(settings: AppSettings) {
        val editor = prefs.edit()
            .putBoolean(KEY_MASTER_ENABLED, settings.masterEnabled)
            .putString(KEY_UNIT, settings.speedUnit.name)
            .putBoolean(KEY_START_ON_BOOT, settings.startOnBoot)
        settings.tiers.forEachIndexed { index, tier ->
            editor
                .putBoolean(keyEnabled(index), tier.enabled)
                .putInt(keySpeed(index), tier.speedThreshold)
                .putInt(keyIncrease(index), tier.volumeIncreaseSteps)
                .putInt(keyDwell(index), tier.dwellSeconds)
        }
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "speed_volume_prefs"
        private const val KEY_MASTER_ENABLED = "master_enabled"
        private const val KEY_UNIT = "speed_unit"
        private const val KEY_START_ON_BOOT = "start_on_boot"

        /** Keys stay "t1_".."t4_" so settings saved by earlier two-tier builds still load. */
        private fun keyEnabled(index: Int) = "t${index + 1}_enabled"
        private fun keySpeed(index: Int) = "t${index + 1}_speed"
        private fun keyIncrease(index: Int) = "t${index + 1}_increase"
        private fun keyDwell(index: Int) = "t${index + 1}_dwell"

        val TIER_DEFAULTS = listOf(
            TierConfig(enabled = true, speedThreshold = 45, volumeIncreaseSteps = 3, dwellSeconds = 5),
            TierConfig(enabled = false, speedThreshold = 90, volumeIncreaseSteps = 4, dwellSeconds = 5),
            TierConfig(enabled = false, speedThreshold = 120, volumeIncreaseSteps = 4, dwellSeconds = 5),
            TierConfig(enabled = false, speedThreshold = 150, volumeIncreaseSteps = 4, dwellSeconds = 5)
        )
    }
}
