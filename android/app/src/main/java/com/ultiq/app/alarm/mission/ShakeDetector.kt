package com.ultiq.app.alarm.mission

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Lightweight accelerometer wrapper that fires [onShake] each time the user
 * shakes the phone hard enough.
 *
 * Phase 5 referenced a shared `PickupDetector` for this; that class was never
 * actually written (the sleep service does its own inline accelerometer
 * handling). So this is a fresh implementation. When a generic detector
 * eventually emerges, the sleep code can adopt it too.
 *
 * Anti-cheat: registered shakes must be at least [minIntervalMs] apart
 * (default 150 ms). Without this, a vibrating surface (laundry machine,
 * massage chair) could feed the accelerometer a high-frequency stream of
 * threshold-crossing samples and drain the counter for free.
 */
class ShakeDetector(
    context: Context,
    private val thresholdMs2: Float,
    private val minIntervalMs: Long = 150L,
    private val onShake: () -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastShakeAt = 0L

    /**
     * §L11: low-pass-filtered gravity estimate, used only when we're falling
     * back to TYPE_ACCELEROMETER. Subtracting this from each axis isolates
     * the dynamic (linear) component of motion, so a horizontal shake against
     * a vertical gravity vector still reads as a high magnitude.
     */
    private val gravity = FloatArray(3)
    private var gravityInitialised = false

    /** Begin listening. No-op if the device has no usable accelerometer. */
    fun start() {
        val s = sensor ?: return
        gravityInitialised = false
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val (linX, linY, linZ) = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Seed the filter on the first sample so we don't spend the first
            // half-second leaking spurious shakes during low-pass settling.
            if (!gravityInitialised) {
                gravity[0] = x; gravity[1] = y; gravity[2] = z
                gravityInitialised = true
            } else {
                gravity[0] = GRAVITY_ALPHA * gravity[0] + (1f - GRAVITY_ALPHA) * x
                gravity[1] = GRAVITY_ALPHA * gravity[1] + (1f - GRAVITY_ALPHA) * y
                gravity[2] = GRAVITY_ALPHA * gravity[2] + (1f - GRAVITY_ALPHA) * z
            }
            Triple(x - gravity[0], y - gravity[1], z - gravity[2])
        } else {
            Triple(x, y, z)
        }

        val mag = sqrt(linX * linX + linY * linY + linZ * linZ)
        if (mag < thresholdMs2) return

        val now = System.currentTimeMillis()
        if (now - lastShakeAt < minIntervalMs) return
        lastShakeAt = now
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Don't care — even LOW accuracy gives us magnitude data we can use.
    }

    companion object {
        /** Standard low-pass smoothing factor for accelerometer-derived gravity. */
        private const val GRAVITY_ALPHA = 0.8f
    }
}
