package com.vigil.wear.monitoring

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.vigil.wear.session.VigilMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Listens to the accelerometer and detects prolonged low movement ("stillness").
 * Runs on a [CoroutineScope] you own (e.g. a foreground service scope) so it stops when the scope is cancelled.
 *
 * This file does not show UI and does not vibrate — it only calls [onStillness].
 */
class StillnessMonitor(private val context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var listener: SensorEventListener? = null
    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null

    private val magnitudeWindow = ArrayDeque<Float>(WINDOW_SIZE)
    private var params: StillnessParams = stillnessParamsFor(VigilMode.Passive)
    @Volatile private var lastVariance: Float? = null

    /** Last time we saw movement above the epsilon band (elapsed realtime ms). */
    private var lastMovementAtElapsedMs: Long = SystemClock.elapsedRealtime()

    fun start(scope: CoroutineScope, mode: VigilMode, onStillness: () -> Unit) {
        stop()
        this.scope = scope
        params = stillnessParamsFor(mode)
        lastMovementAtElapsedMs = SystemClock.elapsedRealtime()
        magnitudeWindow.clear()

        val sensor = accelerometer
        if (sensor == null) {
            Log.w(TAG, "No accelerometer; stillness detection disabled")
            return
        }

        listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    val mag = sqrt(x * x + y * y + z * z)
                    synchronized(magnitudeWindow) {
                        if (magnitudeWindow.size >= WINDOW_SIZE) magnitudeWindow.removeFirst()
                        magnitudeWindow.addLast(mag)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

        sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_UI,
        )

        pollJob =
            scope.launch(Dispatchers.Default) {
                while (isActive) {
                    kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                    val variance: Float? =
                        synchronized(magnitudeWindow) {
                            if (magnitudeWindow.size < WINDOW_SIZE / 2) {
                                lastMovementAtElapsedMs = SystemClock.elapsedRealtime()
                                lastVariance = null
                                null
                            } else {
                                varianceOf(magnitudeWindow).also { lastVariance = it }
                            }
                        }
                    if (variance == null) continue
                    val now = SystemClock.elapsedRealtime()
                    if (variance >= params.movementVarianceEpsilon) {
                        lastMovementAtElapsedMs = now
                    } else if (now - lastMovementAtElapsedMs >= params.stillnessDurationMs) {
                        Log.i(TAG, "Stillness threshold reached (variance=$variance)")
                        onStillness()
                        lastMovementAtElapsedMs = now
                    }
                }
            }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
        scope = null
        magnitudeWindow.clear()
        lastVariance = null
    }

    fun getLastVariance(): Float? = lastVariance

    fun getStillForMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long =
        maxOf(0L, nowElapsedMs - lastMovementAtElapsedMs)

    private fun varianceOf(values: ArrayDeque<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.sum() / values.size
        var acc = 0f
        for (v in values) {
            val d = v - mean
            acc += d * d
        }
        return acc / values.size
    }

    companion object {
        private const val TAG = "Vigil"
        private const val WINDOW_SIZE = 16
        private const val POLL_INTERVAL_MS = 500L
    }
}
