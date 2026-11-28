package com.evolute.telemetrypro.data

data class SocSample(
    val capturedAtMs: Long,
    val soc: Double,
    val odometer: Double,
    val online: Boolean,
    val ready: Boolean,
    val charging: Boolean,
    val batteryTemp: Double?
)
