package com.karaba.power

/** Live values shared between the background service and the screen (same process). */
object MonitorState {
    @Volatile var running = false
    @Volatile var source = Source.UNKNOWN
    @Volatile var sinceMs = 0L
    @Volatile var lux: Double? = null
    @Volatile var flickerPct: Double? = null
    @Volatile var lightStatus = ""
    @Volatile var plugged: Boolean? = null
    @Volatile var sensorUpdatesPerMin = 0
    @Volatile var hasLightSensor = true
    @Volatile var calibrationEndsMs = 0L
    @Volatile var calibrationSource = Source.UNKNOWN
    @Volatile var message: String? = null
}
