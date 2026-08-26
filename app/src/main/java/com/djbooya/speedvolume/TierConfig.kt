package com.djbooya.speedvolume

enum class SpeedUnit { KMH, MPH }

data class TierConfig(
    val enabled: Boolean,
    val speedThreshold: Int,
    val volumeIncreaseSteps: Int,
    val dwellSeconds: Int
)

data class AppSettings(
    val masterEnabled: Boolean,
    val speedUnit: SpeedUnit,
    val startOnBoot: Boolean,
    val tier1: TierConfig,
    val tier2: TierConfig
)
