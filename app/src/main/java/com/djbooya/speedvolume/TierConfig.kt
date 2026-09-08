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
    /** Always [TIER_COUNT] entries, ordered tier 1..4. */
    val tiers: List<TierConfig>
) {
    companion object {
        const val TIER_COUNT = 4
    }
}
