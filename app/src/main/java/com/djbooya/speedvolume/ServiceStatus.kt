package com.djbooya.speedvolume

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpeedState(
    val running: Boolean = false,
    val currentSpeed: Int = 0,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val tier1Engaged: Boolean = false,
    val tier2Engaged: Boolean = false,
    val hasFix: Boolean = false
)

/** In-process, single-app-process bridge between the service and the UI. */
object ServiceStatus {
    private val _state = MutableStateFlow(SpeedState())
    val state: StateFlow<SpeedState> = _state.asStateFlow()

    fun update(transform: (SpeedState) -> SpeedState) {
        _state.value = transform(_state.value)
    }
}
