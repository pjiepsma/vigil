package com.vigil.wear.service

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vigil.wear.R
import com.vigil.wear.classifier.ClassifierInput
import com.vigil.wear.classifier.UserStateClassifier
import com.vigil.wear.data.VigilPreferences
import com.vigil.wear.feedback.VigilHaptics
import com.vigil.wear.metrics.PermissionCapabilityCoordinator
import com.vigil.wear.monitoring.SamsungHealthSensorClient
import com.vigil.wear.monitoring.SamsungProviderState
import com.vigil.wear.monitoring.SamsungSensorReadings
import com.vigil.wear.monitoring.StillnessMonitor
import com.vigil.wear.presentation.MainActivity
import com.vigil.wear.session.SessionMetricsSnapshot
import com.vigil.wear.session.VigilMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service that owns sensor listeners and intervention timing while a session runs.
 * Starting/stopping it is triggered from [com.vigil.wear.session.SessionViewModel].
 */
class VigilSessionService : LifecycleService() {

    /** Created in [onCreate] — [this] is not a valid [Context] in field initializers. */
    private lateinit var stillnessMonitor: StillnessMonitor
    private lateinit var haptics: VigilHaptics
    private var samsungSensorClient: SamsungHealthSensorClient? = null

    private var hapticsEnabled: Boolean = true
    private var cooldownMs: Long = VigilPreferences.DEFAULT_COOLDOWN_MS
    private var lastInterventionElapsedMs: Long = 0L
    private var runningMode: VigilMode = VigilMode.Passive
    private var targetEndEpochMs: Long? = null
    private var isPaused: Boolean = false
    private var endCheckJob: Job? = null
    private var metricsPublishJob: Job? = null
    private var alertCandidateSinceElapsedMs: Long? = null
    private var metricsPublishCount: Long = 0L
    private var spo2MeasurementRunning: Boolean = false
    private var spo2MeasurementStartedElapsedMs: Long? = null
    private var spo2StatusCode: Int? = null
    private var spo2StatusText: String? = null
    private var spo2WarningText: String? = null

    override fun onCreate() {
        super.onCreate()
        stillnessMonitor = StillnessMonitor(this)
        haptics = VigilHaptics(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                endCheckJob?.cancel()
                runningMode =
                    VigilMode.fromId(intent.getStringExtra(EXTRA_MODE) ?: VigilMode.Passive.id)
                        ?: VigilMode.Passive
                hapticsEnabled = intent.getBooleanExtra(EXTRA_HAPTICS, true)
                cooldownMs =
                    intent.getLongExtra(EXTRA_COOLDOWN_MS, VigilPreferences.DEFAULT_COOLDOWN_MS)
                targetEndEpochMs =
                    when {
                        intent.hasExtra(EXTRA_TARGET_END_EPOCH_MS) -> {
                            val v = intent.getLongExtra(EXTRA_TARGET_END_EPOCH_MS, 0L)
                            if (v > 0L) v else null
                        }
                        else -> readSessionTargetEndEpochMs(this)
                    }
                isPaused = false
                writeSessionPaused(this, false)
                val started = startSessionForeground()
                if (!started) {
                    return START_NOT_STICKY
                }
            }
            ACTION_PAUSE -> {
                if (!isPaused) {
                    isPaused = true
                    writeSessionPaused(this, true)
                    stopMonitoringOnly()
                    startAsForeground(paused = true)
                }
            }
            ACTION_RESUME -> {
                if (isPaused) {
                    isPaused = false
                    writeSessionPaused(this, false)
                    val resumed = resumeSessionForeground()
                    if (!resumed) {
                        return START_NOT_STICKY
                    }
                }
            }
            ACTION_STOP -> {
                performFullStop()
            }
            ACTION_START_SPO2 -> {
                startSpo2Measurement()
            }
            ACTION_STOP_SPO2 -> {
                stopSpo2Measurement()
            }
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        stillnessMonitor.start(lifecycleScope, runningMode) { maybeIntervene() }
        startSamsungSensorsIfAvailable()
        startMetricsPublisher()
    }

    private fun stopMonitoringOnly() {
        stillnessMonitor.stop()
        val sensorClient = samsungSensorClient
        samsungSensorClient = null
        lifecycleScope.launch(Dispatchers.Default) { sensorClient?.stop() }
        metricsPublishJob?.cancel()
        metricsPublishJob = null
        metricsPublishCount = 0L
        alertCandidateSinceElapsedMs = null
        spo2MeasurementRunning = false
        spo2MeasurementStartedElapsedMs = null
        spo2StatusCode = null
        spo2StatusText = null
        spo2WarningText = null
        writeMetricsSnapshot(this, SessionMetricsSnapshot())
    }

    private fun scheduleEndCheck() {
        endCheckJob?.cancel()
        val end = targetEndEpochMs ?: return
        endCheckJob =
            lifecycleScope.launch {
                while (isActive) {
                    delay(10_000L)
                    if (!isPaused && System.currentTimeMillis() >= end) {
                        Log.i(TAG, "Session auto-ended at target time")
                        performFullStop()
                        break
                    }
                }
            }
    }

    private fun maybeIntervene() {
        if (isPaused) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastInterventionElapsedMs < cooldownMs) {
            Log.d(TAG, "Intervention skipped (cooldown)")
            return
        }
        if (runningMode == VigilMode.Passive) {
            val bpm = samsungSensorClient?.snapshot()?.heartRateBpm
            if (bpm != null && bpm < PASSIVE_LOW_HR_BPM) {
                Log.i(TAG, "Intervention: stillness + low HR ($bpm)")
            } else {
                Log.i(TAG, "Intervention: stillness")
            }
        } else {
            Log.i(TAG, "Intervention: stillness (${runningMode.id})")
        }
        lastInterventionElapsedMs = now
        if (hapticsEnabled) {
            haptics.playInterventionBuzz()
        }
    }

    private fun startAsForeground(paused: Boolean) {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val text =
            if (paused) {
                getString(R.string.notification_session_paused)
            } else {
                getString(R.string.notification_session_text)
            }
        val builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_session_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_vigil_brand)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
        )
    }

    private fun startSessionForeground(): Boolean {
        return runCatching {
            startAsForeground(paused = false)
            startMonitoring()
            scheduleEndCheck()
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to start session foreground path", throwable)
            performFullStop()
        }.isSuccess
    }

    private fun resumeSessionForeground(): Boolean {
        return runCatching {
            startAsForeground(paused = false)
            startMonitoring()
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to resume session foreground path", throwable)
            performFullStop()
        }.isSuccess
    }

    private fun performFullStop() {
        endCheckJob?.cancel()
        endCheckJob = null
        isPaused = false
        stopMonitoringOnly()
        clearSessionPersistence(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        endCheckJob?.cancel()
        metricsPublishJob?.cancel()
        if (::stillnessMonitor.isInitialized) {
            stopMonitoringOnly()
        }
        super.onDestroy()
    }

    private fun startMetricsPublisher() {
        metricsPublishJob?.cancel()
        metricsPublishJob =
            lifecycleScope.launch(Dispatchers.Default) {
                while (isActive) {
                    publishMetricsSnapshot()
                    delay(METRICS_PUBLISH_INTERVAL_MS)
                }
            }
    }

    private fun publishMetricsSnapshot() {
        metricsPublishCount += 1
        val samsungReadings = samsungSensorClient?.snapshot() ?: SamsungSensorReadings()
        val hr = samsungReadings.heartRateBpm
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = (spo2MeasurementStartedElapsedMs?.let { now - it } ?: 0L).coerceAtLeast(0L)
        if (spo2MeasurementRunning && elapsedMs >= SPO2_MEASUREMENT_DURATION_MS) {
            stopSpo2MeasurementWithStatus(status = "Measurement failed", warning = "Measurement failed")
        }
        val statusCode = samsungReadings.spo2Status ?: spo2StatusCode
        val statusText =
            when (statusCode) {
                SPO2_STATUS_CALCULATING -> "Calculating"
                SPO2_STATUS_DEVICE_MOVING -> "Device moving"
                SPO2_STATUS_LOW_SIGNAL -> "Low signal"
                SPO2_STATUS_MEASUREMENT_COMPLETED -> "Measurement completed"
                else -> spo2StatusText
            }
        val warningText =
            when (statusCode) {
                SPO2_STATUS_DEVICE_MOVING -> "Keep your wrist still"
                SPO2_STATUS_LOW_SIGNAL -> "Low signal quality"
                else -> spo2WarningText
            }
        if (statusCode == SPO2_STATUS_MEASUREMENT_COMPLETED && spo2MeasurementRunning) {
            spo2MeasurementRunning = false
            spo2MeasurementStartedElapsedMs = null
        }
        val providerState =
            when (samsungReadings.providerState) {
                SamsungProviderState.ListeningForData ->
                    if (hr == null && samsungReadings.skinTemperatureC == null) {
                        "Connected: no sensor samples yet"
                    } else {
                        SamsungProviderState.ListeningForData.uiState
                    }
                else -> samsungReadings.providerState.uiState
            }
        val variance = stillnessMonitor.getLastVariance()
        val stillFor = stillnessMonitor.getStillForMs()
        val motionMagnitude = variance?.let { kotlin.math.sqrt(it.toDouble()).toFloat() }
        val posture = if ((motionMagnitude ?: 0f) < 0.03f) "Horizontal" else "Upright"
        val input =
            ClassifierInput(
                heartRateBpm = hr?.toFloat(),
                hrvRmssdMs = samsungReadings.hrvRmssdMs,
                accelerometerMagnitudeG = motionMagnitude,
                skinTempC = samsungReadings.skinTemperatureC,
                postureHorizontal = posture == "Horizontal",
                stillForMs = stillFor,
                speedMs = null,
                cadenceSpm = null,
                spo2Pct = samsungReadings.spo2Pct?.toFloat(),
                elevationChangeMpm = null,
            )
        val result = UserStateClassifier.classify(input, runningMode)
        val drift = result.alertReason != null
        if (drift) {
            if (alertCandidateSinceElapsedMs == null) {
                alertCandidateSinceElapsedMs = now
            }
        } else {
            alertCandidateSinceElapsedMs = null
        }
        val alertConfirmed =
            drift &&
                alertCandidateSinceElapsedMs != null &&
                now - (alertCandidateSinceElapsedMs ?: now) >= ALERT_PERSISTENCE_MS

        if (alertConfirmed && now - lastInterventionElapsedMs >= cooldownMs && hapticsEnabled) {
            haptics.playInterventionBuzz()
            lastInterventionElapsedMs = now
        }

        val snapshot =
            SessionMetricsSnapshot(
                heartRateBpm = hr,
                hrvRmssdMs = samsungReadings.hrvRmssdMs,
                ppgGreenActive = null,
                spo2Pct = samsungReadings.spo2Pct,
                spo2StatusCode = statusCode,
                spo2StatusText = statusText,
                spo2MeasurementRunning = spo2MeasurementRunning || samsungReadings.spo2MeasurementActive,
                spo2ProgressPct =
                    if (spo2MeasurementRunning && spo2MeasurementStartedElapsedMs != null) {
                        ((elapsedMs.toFloat() / SPO2_MEASUREMENT_DURATION_MS.toFloat()) * 100f)
                            .coerceIn(0f, 100f)
                            .roundToInt()
                    } else {
                        null
                    },
                spo2Warning = warningText,
                skinTempC = samsungReadings.skinTemperatureC,
                accelerometerMagnitudeG = motionMagnitude,
                posture = posture,
                barometerHpa = null,
                speedMs = null,
                cadenceSpm = null,
                elevationChangeMpm = null,
                platformUserState = providerState,
                gpsActive = null,
                fallDetected = false,
                classifiedState = result.state,
                modeAlignment = result.modeAlignment,
                confidence = result.confidence,
                alertReason = if (alertConfirmed) result.alertReason else null,
                reasons = result.reasons,
            )
        spo2StatusCode = snapshot.spo2StatusCode
        spo2StatusText = snapshot.spo2StatusText
        spo2WarningText = snapshot.spo2Warning
        writeMetricsSnapshot(this, snapshot)
        if (metricsPublishCount == 1L || metricsPublishCount % 10L == 0L) {
            Log.i(
                TAG,
                "Metrics publish #$metricsPublishCount provider='$providerState' hr=${snapshot.heartRateBpm} hrv=${snapshot.hrvRmssdMs} skin=${snapshot.skinTempC}",
            )
        }
    }

    private fun startSamsungSensorsIfAvailable() {
        if (!PermissionCapabilityCoordinator.canUseSamsungHealthSensors(this)) {
            Log.i(TAG, "Samsung sensor permissions missing; Watch 7 health sensors stay optional")
            return
        }
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching {
                val client = samsungSensorClient ?: SamsungHealthSensorClient(this@VigilSessionService)
                val started = client.start()
                if (started) {
                    samsungSensorClient = client
                } else {
                    samsungSensorClient = null
                    Log.w(TAG, "Samsung sensor client connected but no trackers/listeners started")
                }
            }.onFailure { throwable ->
                Log.w(TAG, "Samsung sensor client failed to start", throwable)
                samsungSensorClient = null
            }
        }
    }

    private fun startSpo2Measurement() {
        val client = samsungSensorClient ?: return
        lifecycleScope.launch(Dispatchers.Default) {
            val started = client.startSpo2Measurement()
            if (started) {
                spo2MeasurementRunning = true
                spo2MeasurementStartedElapsedMs = SystemClock.elapsedRealtime()
                spo2StatusCode = SPO2_STATUS_CALCULATING
                spo2StatusText = "Calculating"
                spo2WarningText = null
            }
        }
    }

    private fun stopSpo2Measurement() {
        stopSpo2MeasurementWithStatus(status = "Stopped", warning = null)
    }

    private fun stopSpo2MeasurementWithStatus(status: String, warning: String?) {
        lifecycleScope.launch(Dispatchers.Default) {
            samsungSensorClient?.stopSpo2Measurement()
            spo2MeasurementRunning = false
            spo2MeasurementStartedElapsedMs = null
            spo2StatusText = status
            spo2WarningText = warning
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch =
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                )
            mgr.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val TAG = "Vigil"
        const val ACTION_START = "com.vigil.wear.action.START_SESSION"
        const val ACTION_STOP = "com.vigil.wear.action.STOP_SESSION"
        const val ACTION_PAUSE = "com.vigil.wear.action.PAUSE_SESSION"
        const val ACTION_RESUME = "com.vigil.wear.action.RESUME_SESSION"
        const val ACTION_START_SPO2 = "com.vigil.wear.action.START_SPO2_MEASUREMENT"
        const val ACTION_STOP_SPO2 = "com.vigil.wear.action.STOP_SPO2_MEASUREMENT"
        const val EXTRA_MODE = "mode"
        const val EXTRA_HAPTICS = "haptics"
        const val EXTRA_COOLDOWN_MS = "cooldown_ms"
        const val EXTRA_TARGET_END_EPOCH_MS = "target_end_epoch_ms"

        private const val CHANNEL_ID = "vigil_session"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "vigil_session_flags"
        private const val KEY_START_MS = "session_start_epoch_ms"
        private const val KEY_TARGET_END_MS = "session_target_end_epoch_ms"
        private const val KEY_PAUSED = "session_paused"
        private const val KEY_METRIC_HEART_RATE = "metric_heart_rate"
        private const val KEY_METRIC_HRV = "metric_hrv"
        private const val KEY_METRIC_SKIN_TEMP = "metric_skin_temp"
        private const val KEY_METRIC_SPO2 = "metric_spo2"
        private const val KEY_METRIC_SPO2_STATUS_CODE = "metric_spo2_status_code"
        private const val KEY_METRIC_SPO2_STATUS_TEXT = "metric_spo2_status_text"
        private const val KEY_METRIC_SPO2_RUNNING = "metric_spo2_running"
        private const val KEY_METRIC_SPO2_PROGRESS = "metric_spo2_progress"
        private const val KEY_METRIC_SPO2_WARNING = "metric_spo2_warning"
        private const val KEY_METRIC_ACCEL = "metric_accel"
        private const val KEY_METRIC_POSTURE = "metric_posture"
        private const val KEY_METRIC_PLATFORM_STATE = "metric_platform_state"
        private const val KEY_METRIC_CLASSIFIED_STATE = "metric_classified_state"
        private const val KEY_METRIC_ALIGNMENT = "metric_alignment"
        private const val KEY_METRIC_CONFIDENCE = "metric_confidence"
        private const val KEY_METRIC_ALERT_REASON = "metric_alert_reason"
        private const val KEY_METRIC_REASONS = "metric_reasons"
        private const val PASSIVE_LOW_HR_BPM = 50
        private const val METRICS_PUBLISH_INTERVAL_MS = 2_000L
        private const val ALERT_PERSISTENCE_MS = 8_000L
        private const val SPO2_MEASUREMENT_DURATION_MS = 35_000L
        private const val SPO2_STATUS_LOW_SIGNAL = -5
        private const val SPO2_STATUS_DEVICE_MOVING = -4
        private const val SPO2_STATUS_CALCULATING = 0
        private const val SPO2_STATUS_MEASUREMENT_COMPLETED = 2

        fun start(
            context: Context,
            mode: VigilMode,
            hapticsEnabled: Boolean,
            cooldownMs: Long,
            targetEndEpochMs: Long?,
        ) {
            val i =
                Intent(context, VigilSessionService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_MODE, mode.id)
                    putExtra(EXTRA_HAPTICS, hapticsEnabled)
                    putExtra(EXTRA_COOLDOWN_MS, cooldownMs)
                    if (targetEndEpochMs != null && targetEndEpochMs > 0L) {
                        putExtra(EXTRA_TARGET_END_EPOCH_MS, targetEndEpochMs)
                    }
                }
            ContextCompat.startForegroundService(context, i)
        }

        fun pause(context: Context) {
            if (!isRunning(context)) return
            context.startService(
                Intent(context, VigilSessionService::class.java).apply { action = ACTION_PAUSE },
            )
        }

        fun resume(context: Context) {
            if (!isRunning(context)) return
            context.startService(
                Intent(context, VigilSessionService::class.java).apply { action = ACTION_RESUME },
            )
        }

        fun startSpo2Measurement(context: Context) {
            if (!isRunning(context)) return
            context.startService(
                Intent(context, VigilSessionService::class.java).apply { action = ACTION_START_SPO2 },
            )
        }

        fun stopSpo2Measurement(context: Context) {
            if (!isRunning(context)) return
            context.startService(
                Intent(context, VigilSessionService::class.java).apply { action = ACTION_STOP_SPO2 },
            )
        }

        fun stop(context: Context) {
            if (!isRunning(context)) return
            context.startService(
                Intent(context, VigilSessionService::class.java).apply { action = ACTION_STOP },
            )
        }

        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            return am.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == VigilSessionService::class.java.name
            }
        }

        fun writeSessionStartEpochMs(context: Context, ms: Long) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_START_MS, ms).apply()
        }

        fun readSessionStartEpochMs(context: Context): Long? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.contains(KEY_START_MS)) return null
            return p.getLong(KEY_START_MS, 0L)
        }

        fun writeSessionTargetEndEpochMs(context: Context, ms: Long?) {
            val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (ms == null || ms <= 0L) {
                e.remove(KEY_TARGET_END_MS)
            } else {
                e.putLong(KEY_TARGET_END_MS, ms)
            }
            e.apply()
        }

        fun readSessionTargetEndEpochMs(context: Context): Long? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.contains(KEY_TARGET_END_MS)) return null
            val v = p.getLong(KEY_TARGET_END_MS, 0L)
            return if (v <= 0L) null else v
        }

        fun writeSessionPaused(context: Context, paused: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAUSED, paused).apply()
        }

        fun readSessionPaused(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)

        /** Clears all session-related keys written by this service (start time, target end, paused). */
        fun clearSessionPersistence(context: Context) {
            context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_START_MS)
                .remove(KEY_TARGET_END_MS)
                .remove(KEY_PAUSED)
                .remove(KEY_METRIC_HEART_RATE)
                .remove(KEY_METRIC_HRV)
                .remove(KEY_METRIC_SKIN_TEMP)
                .remove(KEY_METRIC_SPO2)
                .remove(KEY_METRIC_SPO2_STATUS_CODE)
                .remove(KEY_METRIC_SPO2_STATUS_TEXT)
                .remove(KEY_METRIC_SPO2_RUNNING)
                .remove(KEY_METRIC_SPO2_PROGRESS)
                .remove(KEY_METRIC_SPO2_WARNING)
                .remove(KEY_METRIC_ACCEL)
                .remove(KEY_METRIC_POSTURE)
                .remove(KEY_METRIC_PLATFORM_STATE)
                .remove(KEY_METRIC_CLASSIFIED_STATE)
                .remove(KEY_METRIC_ALIGNMENT)
                .remove(KEY_METRIC_CONFIDENCE)
                .remove(KEY_METRIC_ALERT_REASON)
                .remove(KEY_METRIC_REASONS)
                .apply()
        }

        fun writeMetricsSnapshot(context: Context, snapshot: SessionMetricsSnapshot) {
            val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (snapshot.heartRateBpm == null) e.remove(KEY_METRIC_HEART_RATE) else e.putInt(KEY_METRIC_HEART_RATE, snapshot.heartRateBpm)
            if (snapshot.hrvRmssdMs == null) e.remove(KEY_METRIC_HRV) else e.putFloat(KEY_METRIC_HRV, snapshot.hrvRmssdMs)
            if (snapshot.skinTempC == null) e.remove(KEY_METRIC_SKIN_TEMP) else e.putFloat(KEY_METRIC_SKIN_TEMP, snapshot.skinTempC)
            if (snapshot.spo2Pct == null) e.remove(KEY_METRIC_SPO2) else e.putInt(KEY_METRIC_SPO2, snapshot.spo2Pct)
            if (snapshot.spo2StatusCode == null) e.remove(KEY_METRIC_SPO2_STATUS_CODE) else e.putInt(KEY_METRIC_SPO2_STATUS_CODE, snapshot.spo2StatusCode)
            if (snapshot.spo2StatusText == null) e.remove(KEY_METRIC_SPO2_STATUS_TEXT) else e.putString(KEY_METRIC_SPO2_STATUS_TEXT, snapshot.spo2StatusText)
            e.putBoolean(KEY_METRIC_SPO2_RUNNING, snapshot.spo2MeasurementRunning)
            if (snapshot.spo2ProgressPct == null) e.remove(KEY_METRIC_SPO2_PROGRESS) else e.putInt(KEY_METRIC_SPO2_PROGRESS, snapshot.spo2ProgressPct)
            if (snapshot.spo2Warning == null) e.remove(KEY_METRIC_SPO2_WARNING) else e.putString(KEY_METRIC_SPO2_WARNING, snapshot.spo2Warning)
            if (snapshot.accelerometerMagnitudeG == null) e.remove(KEY_METRIC_ACCEL) else e.putFloat(KEY_METRIC_ACCEL, snapshot.accelerometerMagnitudeG)
            if (snapshot.posture == null) e.remove(KEY_METRIC_POSTURE) else e.putString(KEY_METRIC_POSTURE, snapshot.posture)
            if (snapshot.platformUserState == null) e.remove(KEY_METRIC_PLATFORM_STATE) else e.putString(KEY_METRIC_PLATFORM_STATE, snapshot.platformUserState)
            if (snapshot.classifiedState == null) e.remove(KEY_METRIC_CLASSIFIED_STATE) else e.putString(KEY_METRIC_CLASSIFIED_STATE, snapshot.classifiedState.id)
            if (snapshot.modeAlignment == null) e.remove(KEY_METRIC_ALIGNMENT) else e.putString(KEY_METRIC_ALIGNMENT, snapshot.modeAlignment.name)
            if (snapshot.confidence == null) e.remove(KEY_METRIC_CONFIDENCE) else e.putFloat(KEY_METRIC_CONFIDENCE, snapshot.confidence)
            if (snapshot.alertReason == null) e.remove(KEY_METRIC_ALERT_REASON) else e.putString(KEY_METRIC_ALERT_REASON, snapshot.alertReason)
            if (snapshot.reasons.isEmpty()) e.remove(KEY_METRIC_REASONS) else e.putString(KEY_METRIC_REASONS, snapshot.reasons.joinToString("||"))
            e.apply()
        }

        fun readMetricsSnapshot(context: Context): SessionMetricsSnapshot {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val classifiedId = p.getString(KEY_METRIC_CLASSIFIED_STATE, null)
            val classified =
                classifiedId?.let { id ->
                    com.vigil.wear.classifier.AppUserState.values().find { it.id == id }
                }
            val alignmentName = p.getString(KEY_METRIC_ALIGNMENT, null)
            val alignment =
                alignmentName?.let { name ->
                    runCatching { com.vigil.wear.classifier.ModeAlignment.valueOf(name) }.getOrNull()
                }
            val reasons =
                p.getString(KEY_METRIC_REASONS, null)
                    ?.split("||")
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
            return SessionMetricsSnapshot(
                heartRateBpm = if (p.contains(KEY_METRIC_HEART_RATE)) p.getInt(KEY_METRIC_HEART_RATE, 0) else null,
                hrvRmssdMs = if (p.contains(KEY_METRIC_HRV)) p.getFloat(KEY_METRIC_HRV, 0f) else null,
                skinTempC = if (p.contains(KEY_METRIC_SKIN_TEMP)) p.getFloat(KEY_METRIC_SKIN_TEMP, 0f) else null,
                spo2Pct = if (p.contains(KEY_METRIC_SPO2)) p.getInt(KEY_METRIC_SPO2, 0) else null,
                accelerometerMagnitudeG = if (p.contains(KEY_METRIC_ACCEL)) p.getFloat(KEY_METRIC_ACCEL, 0f) else null,
                posture = p.getString(KEY_METRIC_POSTURE, null),
                platformUserState = p.getString(KEY_METRIC_PLATFORM_STATE, null),
                classifiedState = classified,
                modeAlignment = alignment,
                confidence = if (p.contains(KEY_METRIC_CONFIDENCE)) p.getFloat(KEY_METRIC_CONFIDENCE, 0f) else null,
                alertReason = p.getString(KEY_METRIC_ALERT_REASON, null),
                reasons = reasons,
                spo2StatusCode = if (p.contains(KEY_METRIC_SPO2_STATUS_CODE)) p.getInt(KEY_METRIC_SPO2_STATUS_CODE, 0) else null,
                spo2StatusText = p.getString(KEY_METRIC_SPO2_STATUS_TEXT, null),
                spo2MeasurementRunning = p.getBoolean(KEY_METRIC_SPO2_RUNNING, false),
                spo2ProgressPct = if (p.contains(KEY_METRIC_SPO2_PROGRESS)) p.getInt(KEY_METRIC_SPO2_PROGRESS, 0) else null,
                spo2Warning = p.getString(KEY_METRIC_SPO2_WARNING, null),
            )
        }
    }
}
