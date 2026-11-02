package com.kahraba.power

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

    // The last few minutes of readings, for the live light graph (the saved files lag a little).
    private const val RECENT_MS = 15 * 60_000L
    private val recentReadings = ArrayDeque<LightHistory.Reading>()
    private val recentStatus = ArrayDeque<LightHistory.Status>()

    fun addRecentReading(timeMs: Long, lux: Double) = synchronized(this) {
        recentReadings.addLast(LightHistory.Reading(timeMs, lux))
        while (recentReadings.isNotEmpty() && recentReadings.first().timeMs < timeMs - RECENT_MS) recentReadings.removeFirst()
    }

    fun addRecentStatus(timeMs: Long, source: Source, chargerOff: Boolean) = synchronized(this) {
        recentStatus.addLast(LightHistory.Status(timeMs, source, chargerOff))
        while (recentStatus.isNotEmpty() && recentStatus.first().timeMs < timeMs - RECENT_MS) recentStatus.removeFirst()
    }

    fun recent(): Pair<List<LightHistory.Reading>, List<LightHistory.Status>> = synchronized(this) {
        recentReadings.toList() to recentStatus.toList()
    }
}
