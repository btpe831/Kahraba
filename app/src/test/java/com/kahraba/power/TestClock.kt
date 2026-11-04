package com.kahraba.power

/** The local hour in a replayed recording whose times are ms from [originHour]:00. */
fun recordingClock(originHour: Int): (Long) -> Int = { ((originHour + it / 3_600_000) % 24).toInt() }
