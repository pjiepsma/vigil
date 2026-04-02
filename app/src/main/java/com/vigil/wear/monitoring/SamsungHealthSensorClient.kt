package com.vigil.wear.monitoring

import android.content.Context
import android.os.Build
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

enum class SamsungProviderState(val uiState: String) {
    Disconnected("Disconnected"),
    Connecting("Connecting to Samsung Health"),
    ConnectedNoSupportedTrackers("Connected: no matching trackers"),
    ListeningForData("Connected: waiting for sensor samples"),
    HeartRateDataFlowing("Connected: heart-rate data flowing"),
    SkinTemperatureDataFlowing("Connected: skin-temperature data flowing"),
    SpO2Ready("Connected: SpO2 ready"),
    SpO2Measuring("Connected: measuring SpO2"),
    SpO2Completed("Connected: SpO2 measurement completed"),
    TrackerRegistrationFailed("Connected: tracker setup failed"),
    ConnectionFailed("Samsung Health connection failed"),
    Error("Samsung sensor error"),
}

data class SamsungSensorReadings(
    val heartRateBpm: Int? = null,
    val hrvRmssdMs: Float? = null,
    val skinTemperatureC: Float? = null,
    val ambientTemperatureC: Float? = null,
    val heartRateStatus: Int? = null,
    val skinTemperatureStatus: Int? = null,
    val spo2Pct: Int? = null,
    val spo2Status: Int? = null,
    val spo2TrackerAvailable: Boolean = false,
    val spo2MeasurementActive: Boolean = false,
    val spo2Error: String? = null,
    val providerState: SamsungProviderState = SamsungProviderState.Disconnected,
)

class SamsungHealthSensorClient(context: Context) {
    private val appContext = context.applicationContext
    private val latestReadings = AtomicReference(SamsungSensorReadings())

    @Volatile private var healthTrackingService: HealthTrackingService? = null
    @Volatile private var heartRateTracker: HealthTracker? = null
    @Volatile private var skinTemperatureTracker: HealthTracker? = null
    @Volatile private var spo2Tracker: HealthTracker? = null
    @Volatile private var heartRateListener: HealthTracker.TrackerEventListener? = null
    @Volatile private var skinTemperatureListener: HealthTracker.TrackerEventListener? = null
    @Volatile private var spo2Listener: HealthTracker.TrackerEventListener? = null
    @Volatile private var heartRateCallbackCount: Long = 0L
    @Volatile private var skinTemperatureCallbackCount: Long = 0L
    @Volatile private var spo2CallbackCount: Long = 0L

    suspend fun start(): Boolean =
        withContext(Dispatchers.Default) {
            if (!isSupportedWatch()) {
                Log.i(TAG, "Samsung sensor client disabled: non-Samsung watch build/runtime")
                return@withContext false
            }
            stopBlocking()
            updateProviderState(SamsungProviderState.Connecting)
            runCatching {
                val service = connectService() ?: return@runCatching false
                healthTrackingService = service
                val anyTrackerStarted = registerTrackers(service)
                initializeSpo2Tracker(service)
                anyTrackerStarted || latestReadings.get().spo2TrackerAvailable
            }.onFailure { throwable ->
                Log.w(TAG, "Samsung sensor client failed to start", throwable)
                updateProviderState(SamsungProviderState.Error)
                stopBlocking()
            }.getOrDefault(false)
        }

    suspend fun stop() {
        withContext(Dispatchers.Default) {
            stopBlocking()
        }
    }

    fun snapshot(): SamsungSensorReadings = latestReadings.get()

    fun startSpo2Measurement(): Boolean {
        val tracker = spo2Tracker ?: return false
        if (!latestReadings.get().spo2TrackerAvailable) return false
        return runCatching {
            tracker.flush()
            updateReadings { current ->
                current.copy(
                    spo2MeasurementActive = true,
                    spo2Status = SPO2_CALCULATING,
                    spo2Pct = null,
                    spo2Error = null,
                    providerState = SamsungProviderState.SpO2Measuring,
                )
            }
            true
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to start SpO2 measurement", throwable)
            updateReadings { current ->
                current.copy(
                    spo2MeasurementActive = false,
                    spo2Error = throwable.message ?: "SpO2 start failed",
                    providerState = SamsungProviderState.Error,
                )
            }
        }.getOrDefault(false)
    }

    fun stopSpo2Measurement() {
        updateReadings { current ->
            current.copy(
                spo2MeasurementActive = false,
                providerState = current.providerState.takeUnless { it == SamsungProviderState.SpO2Measuring }
                    ?: SamsungProviderState.ListeningForData,
            )
        }
    }

    private suspend fun connectService(): HealthTrackingService? {
        val service =
            withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val completed = AtomicBoolean(false)
                    lateinit var createdService: HealthTrackingService
                    val listener =
                        object : ConnectionListener {
                            override fun onConnectionSuccess() {
                                if (completed.compareAndSet(false, true)) {
                                    continuation.resume(createdService)
                                }
                            }

                            override fun onConnectionEnded() {
                                if (completed.compareAndSet(false, true)) {
                                    continuation.resumeWithException(
                                        IllegalStateException("Samsung Health connection ended"),
                                    )
                                }
                            }

                            override fun onConnectionFailed(exception: HealthTrackerException) {
                                if (completed.compareAndSet(false, true)) {
                                    continuation.resumeWithException(
                                        IllegalStateException(
                                            "Samsung Health connection failed: ${exception.message}",
                                            exception,
                                        ),
                                    )
                                }
                            }
                        }
                    createdService = HealthTrackingService(listener, appContext)
                    continuation.invokeOnCancellation {
                        runCatching { createdService.disconnectService() }
                    }
                    createdService.connectService()
                }
            }
        if (service == null) {
            updateProviderState(SamsungProviderState.ConnectionFailed)
            Log.w(TAG, "Samsung Health connection timed out")
        }
        return service
    }

    private fun registerTrackers(service: HealthTrackingService): Boolean {
        val supportedTrackers = service.trackingCapability.supportHealthTrackerTypes.toSet()
        Log.i(
            TAG,
            "Samsung supported trackers: ${supportedTrackers.joinToString { it.name }.ifBlank { "(none)" }}",
        )

        val heartRateStarted = startHeartRateTracker(service, supportedTrackers)
        val skinTempStarted = startSkinTemperatureTracker(service, supportedTrackers)
        val anyTrackerStarted = heartRateStarted || skinTempStarted
        when {
            anyTrackerStarted -> updateProviderState(SamsungProviderState.ListeningForData)
            supportedTrackers.isEmpty() -> updateProviderState(SamsungProviderState.ConnectedNoSupportedTrackers)
            else -> updateProviderState(SamsungProviderState.TrackerRegistrationFailed)
        }
        return anyTrackerStarted
    }

    private fun initializeSpo2Tracker(service: HealthTrackingService) {
        val supportedTrackers = service.trackingCapability.supportHealthTrackerTypes.toSet()
        if (!supportedTrackers.contains(HealthTrackerType.SPO2_ON_DEMAND)) {
            updateReadings { current -> current.copy(spo2TrackerAvailable = false) }
            return
        }
        runCatching {
            val tracker = service.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
            val listener =
                object : HealthTracker.TrackerEventListener {
                    override fun onDataReceived(dataPoints: List<DataPoint>) {
                        spo2CallbackCount += 1
                        dataPoints.forEach(::updateSpo2)
                    }

                    override fun onFlushCompleted() = Unit

                    override fun onError(trackerError: HealthTracker.TrackerError) {
                        val errorMessage =
                            when (trackerError) {
                                HealthTracker.TrackerError.PERMISSION_ERROR -> "SpO2 permission denied"
                                HealthTracker.TrackerError.SDK_POLICY_ERROR -> "SpO2 blocked by SDK policy"
                                else -> "SpO2 tracker error: $trackerError"
                            }
                        Log.w(TAG, errorMessage)
                        updateReadings { current ->
                            current.copy(
                                spo2MeasurementActive = false,
                                spo2Error = errorMessage,
                                providerState = SamsungProviderState.Error,
                            )
                        }
                    }
                }
            tracker.setEventListener(listener)
            spo2Tracker = tracker
            spo2Listener = listener
            updateReadings { current ->
                current.copy(
                    spo2TrackerAvailable = true,
                    providerState =
                        if (current.providerState == SamsungProviderState.ListeningForData) {
                            SamsungProviderState.SpO2Ready
                        } else {
                            current.providerState
                        },
                )
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to initialize SpO2 tracker", throwable)
            updateReadings { current ->
                current.copy(
                    spo2TrackerAvailable = false,
                    spo2Error = throwable.message,
                )
            }
        }
    }

    private fun startHeartRateTracker(
        service: HealthTrackingService,
        supportedTrackers: Set<HealthTrackerType>,
    ): Boolean {
        val trackerType =
            HEART_RATE_PRIORITY.firstOrNull { candidate ->
                supportedTrackers.contains(candidate)
            } ?: run {
                Log.i(TAG, "Samsung watch does not expose ${HEART_RATE_PRIORITY.joinToString { it.name }}")
                return false
            }
        return runCatching {
            val tracker = service.getHealthTracker(trackerType)
            val listener =
                object : HealthTracker.TrackerEventListener {
                    override fun onDataReceived(dataPoints: List<DataPoint>) {
                        heartRateCallbackCount += 1
                        if (heartRateCallbackCount == 1L || heartRateCallbackCount % 10L == 0L) {
                            Log.i(TAG, "Heart-rate callback #$heartRateCallbackCount points=${dataPoints.size}")
                        }
                        dataPoints.forEach(::updateHeartRate)
                    }

                    override fun onFlushCompleted() = Unit

                    override fun onError(trackerError: HealthTracker.TrackerError) {
                        Log.w(TAG, "Samsung heart-rate tracker error: $trackerError")
                    }
                }
            heartRateListener = listener
            tracker.setEventListener(listener)
            heartRateTracker = tracker
            Log.i(TAG, "Registered Samsung tracker ${trackerType.name}")
            runCatching {
                tracker.flush()
                Log.i(TAG, "Requested immediate flush for ${trackerType.name}")
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to flush ${trackerType.name}", throwable)
            }
            true
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to register tracker ${trackerType.name}", throwable)
        }.getOrDefault(false)
    }

    private fun startSkinTemperatureTracker(
        service: HealthTrackingService,
        supportedTrackers: Set<HealthTrackerType>,
    ): Boolean {
        val trackerType =
            SKIN_TEMPERATURE_PRIORITY.firstOrNull { candidate ->
                supportedTrackers.contains(candidate)
            } ?: run {
                Log.i(TAG, "Samsung watch does not expose ${SKIN_TEMPERATURE_PRIORITY.joinToString { it.name }}")
                return false
            }
        return runCatching {
            val tracker = service.getHealthTracker(trackerType)
            val listener =
                object : HealthTracker.TrackerEventListener {
                    override fun onDataReceived(dataPoints: List<DataPoint>) {
                        skinTemperatureCallbackCount += 1
                        if (skinTemperatureCallbackCount == 1L || skinTemperatureCallbackCount % 10L == 0L) {
                            Log.i(TAG, "Skin-temperature callback #$skinTemperatureCallbackCount points=${dataPoints.size}")
                        }
                        dataPoints.forEach(::updateSkinTemperature)
                    }

                    override fun onFlushCompleted() = Unit

                    override fun onError(trackerError: HealthTracker.TrackerError) {
                        Log.w(TAG, "Samsung skin-temperature tracker error: $trackerError")
                    }
                }
            skinTemperatureListener = listener
            tracker.setEventListener(listener)
            skinTemperatureTracker = tracker
            Log.i(TAG, "Registered Samsung tracker ${trackerType.name}")
            true
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to register tracker ${trackerType.name}", throwable)
        }.getOrDefault(false)
    }

    private fun updateHeartRate(dataPoint: DataPoint) {
        val status: Int? = readValue(dataPoint, ValueKey.HeartRateSet.HEART_RATE_STATUS)
        val heartRate: Int? = readValue(dataPoint, ValueKey.HeartRateSet.HEART_RATE)
        val ibiValues: List<Int> = readValue(dataPoint, ValueKey.HeartRateSet.IBI_LIST).orEmpty()
        val ibiStatuses: List<Int> = readValue(dataPoint, ValueKey.HeartRateSet.IBI_STATUS_LIST).orEmpty()
        if (status != null && status != SUCCESSFUL_MEASUREMENT_STATUS && heartRate == null && ibiValues.isEmpty()) {
            return
        }
        val rmssd = computeRmssd(ibiValues = ibiValues, ibiStatuses = ibiStatuses)

        updateReadings { current ->
            current.copy(
                heartRateBpm = heartRate ?: current.heartRateBpm,
                hrvRmssdMs = rmssd ?: current.hrvRmssdMs,
                heartRateStatus = status ?: current.heartRateStatus,
                providerState = SamsungProviderState.HeartRateDataFlowing,
            )
        }
    }

    private fun updateSkinTemperature(dataPoint: DataPoint) {
        val status: Int? = readValue(dataPoint, ValueKey.SkinTemperatureSet.STATUS)
        val wrist: Float? = readValue(dataPoint, ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
        val ambient: Float? = readValue(dataPoint, ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
        if (status != null && status != SUCCESSFUL_MEASUREMENT_STATUS && wrist == null && ambient == null) {
            return
        }
        updateReadings { current ->
            current.copy(
                skinTemperatureC = wrist ?: current.skinTemperatureC,
                ambientTemperatureC = ambient ?: current.ambientTemperatureC,
                skinTemperatureStatus = status ?: current.skinTemperatureStatus,
                providerState = SamsungProviderState.SkinTemperatureDataFlowing,
            )
        }
    }

    private fun updateSpo2(dataPoint: DataPoint) {
        val status: Int? = readValue(dataPoint, ValueKey.SpO2Set.STATUS)
        val value: Int? = readValue(dataPoint, ValueKey.SpO2Set.SPO2)
        updateReadings { current ->
            val completed = status == SPO2_MEASUREMENT_COMPLETED
            val warning =
                when (status) {
                    SPO2_DEVICE_MOVING -> "Keep your wrist still"
                    SPO2_LOW_SIGNAL -> "Low SpO2 signal quality"
                    else -> null
                }
            current.copy(
                spo2Status = status ?: current.spo2Status,
                spo2Pct = if (completed) value ?: current.spo2Pct else current.spo2Pct,
                spo2MeasurementActive = if (completed) false else current.spo2MeasurementActive,
                spo2Error = warning ?: current.spo2Error,
                providerState =
                    when {
                        completed -> SamsungProviderState.SpO2Completed
                        status == SPO2_CALCULATING -> SamsungProviderState.SpO2Measuring
                        status == SPO2_DEVICE_MOVING || status == SPO2_LOW_SIGNAL -> SamsungProviderState.SpO2Measuring
                        else -> current.providerState
                    },
            )
        }
    }

    private fun <T> readValue(dataPoint: DataPoint, key: ValueKey<T>): T? =
        runCatching { dataPoint.getValue(key) }.getOrNull()

    private fun computeRmssd(ibiValues: List<Int>, ibiStatuses: List<Int>): Float? {
        val filtered =
            if (ibiStatuses.size == ibiValues.size) {
                ibiValues.zip(ibiStatuses).mapNotNull { (ibi, status) ->
                    if (status == SUCCESSFUL_MEASUREMENT_STATUS && ibi > 0) ibi.toFloat() else null
                }
            } else {
                ibiValues.filter { it > 0 }.map(Int::toFloat)
            }
        if (filtered.size < 2) return null
        val squaredDiffs =
            filtered.zipWithNext { a, b ->
                val diff = b - a
                diff * diff
            }
        if (squaredDiffs.isEmpty()) return null
        return sqrt(squaredDiffs.average()).toFloat()
    }

    private fun stopBlocking() {
        runCatching { heartRateTracker?.unsetEventListener() }
        runCatching { skinTemperatureTracker?.unsetEventListener() }
        runCatching { spo2Tracker?.unsetEventListener() }
        runCatching { healthTrackingService?.disconnectService() }
        heartRateTracker = null
        skinTemperatureTracker = null
        spo2Tracker = null
        heartRateListener = null
        skinTemperatureListener = null
        spo2Listener = null
        healthTrackingService = null
        heartRateCallbackCount = 0L
        skinTemperatureCallbackCount = 0L
        spo2CallbackCount = 0L
        latestReadings.set(SamsungSensorReadings())
    }

    private fun updateReadings(transform: (SamsungSensorReadings) -> SamsungSensorReadings) {
        while (true) {
            val current = latestReadings.get()
            val updated = transform(current)
            if (latestReadings.compareAndSet(current, updated)) {
                return
            }
        }
    }

    private fun updateProviderState(state: SamsungProviderState) {
        updateReadings { current -> current.copy(providerState = state) }
    }

    companion object {
        private const val TAG = "Vigil"
        private const val CONNECTION_TIMEOUT_MS = 5_000L
        private const val SUCCESSFUL_MEASUREMENT_STATUS = 1
        private const val SPO2_LOW_SIGNAL = -5
        private const val SPO2_DEVICE_MOVING = -4
        private const val SPO2_CALCULATING = 0
        private const val SPO2_MEASUREMENT_COMPLETED = 2
        private val HEART_RATE_PRIORITY =
            listOf(
                HealthTrackerType.HEART_RATE_CONTINUOUS,
                HealthTrackerType.HEART_RATE,
            )
        private val SKIN_TEMPERATURE_PRIORITY =
            listOf(
                HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND,
                HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS,
            )

        fun isSupportedWatch(): Boolean =
            Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }
}
