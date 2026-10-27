package com.karaba.power

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import kotlin.math.sqrt

/**
 * Tells when the phone is picked up or moved, using the accelerometer. Gravity is tracked with a
 * slow filter; a jolt above [THRESHOLD] (m/s²) on top of it counts as movement.
 */
class MotionWatcher(private val sensors: SensorManager, private val onMoved: () -> Unit) : SensorEventListener {
    private val gravity = FloatArray(3)
    private var primed = 0

    fun start(handler: Handler? = null): Boolean {
        val acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        primed = 0
        return if (handler != null) {
            sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI, handler)
        } else {
            sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        for (i in 0..2) gravity[i] = if (primed == 0) e.values[i] else gravity[i] * 0.9f + e.values[i] * 0.1f
        val x = e.values[0] - gravity[0]
        val y = e.values[1] - gravity[1]
        val z = e.values[2] - gravity[2]
        // Ignore the first readings while the filter settles.
        if (++primed > 10 && sqrt(x * x + y * y + z * z) > THRESHOLD) onMoved()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        const val THRESHOLD = 0.8f
    }
}
