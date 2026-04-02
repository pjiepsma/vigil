package com.vigil.wear.monitoring

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPoint
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.SampleDataPoint
import kotlinx.coroutines.guava.await
import java.util.concurrent.atomic.AtomicReference

/**
 * Registers a passive heart-rate listener via Health Services when the device supports it.
 * [getLastBpm] is updated from background callbacks and can refine passive-mode heuristics.
 */
class PassiveHeartRateCollector(context: Context) {

    private val appContext = context.applicationContext
    private val client = HealthServices.getClient(appContext)
    private val lastBpm = AtomicReference<Int?>(null)

    fun getLastBpm(): Int? = lastBpm.get()

    suspend fun startIfSupported(): Boolean {
        return try {
            val passive = client.passiveMonitoringClient
            val caps = passive.getCapabilitiesAsync().await()
            if (DataType.HEART_RATE_BPM !in caps.supportedDataTypesPassiveMonitoring) {
                Log.i(TAG, "Passive HR not in capabilities for this device")
                return false
            }
            val config =
                PassiveListenerConfig.builder()
                    .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                    .build()
            val callback =
                object : PassiveListenerCallback {
                    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
                        for (dp in dataPoints.getData(DataType.HEART_RATE_BPM)) {
                            val bpm = extractBpm(dp) ?: continue
                            lastBpm.set(bpm)
                        }
                    }
                }
            passive.setPassiveListenerCallback(config, callback)
            Log.i(TAG, "Passive HR listener registered")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Passive HR unavailable: ${t.message}")
            false
        }
    }

    private fun extractBpm(dp: DataPoint<*>): Int? {
        return when (dp) {
            is SampleDataPoint<*> -> {
                val n = dp.value as? Number ?: return null
                n.toInt().coerceIn(30, 220)
            }
            else -> null
        }
    }

    suspend fun stop() {
        try {
            client.passiveMonitoringClient.clearPassiveListenerCallbackAsync().await()
        } catch (_: Throwable) {
            // Already cleared or client gone
        }
        lastBpm.set(null)
    }

    companion object {
        private const val TAG = "Vigil"
    }
}
