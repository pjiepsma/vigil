package com.vigil.wear.session

import android.app.Application
import android.Manifest
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigil.wear.R
import com.vigil.wear.VigilApplication
import com.vigil.wear.data.VigilPreferences
import com.vigil.wear.feedback.TestBuzzOutcome
import com.vigil.wear.feedback.VigilHaptics
import com.vigil.wear.metrics.MetricCatalog
import com.vigil.wear.metrics.PermissionCapabilityCoordinator
import com.vigil.wear.service.VigilSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Holds everything the UI needs about the current Vigil session and mirrors a few preferences (haptics).
 * Starting a session starts [VigilSessionService] so monitoring can continue in the background.
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val readHeartRatePermission = "android.permission.health.READ_HEART_RATE"
    private val readOxygenSaturationPermission = "android.permission.health.READ_OXYGEN_SATURATION"


    private val prefs: VigilPreferences =
        (application as? VigilApplication)?.preferences
            ?: throw IllegalStateException("SessionViewModel requires VigilApplication")
    private val haptics = VigilHaptics(application)

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    init {
        prefs.hapticsEnabled
            .onEach { enabled -> _uiState.update { it.copy(hapticsEnabled = enabled) } }
            .launchIn(viewModelScope)

        prefs.lastModeId
            .onEach { id ->
                val mode = id?.let { VigilMode.fromId(it) }
                _uiState.update { it.copy(selectedMode = mode ?: it.selectedMode) }
            }
            .launchIn(viewModelScope)

        if (VigilSessionService.isRunning(application)) {
            val startMs =
                VigilSessionService.readSessionStartEpochMs(application)
                    ?: System.currentTimeMillis()
            val end = VigilSessionService.readSessionTargetEndEpochMs(application)
            val paused = VigilSessionService.readSessionPaused(application)
            _uiState.update {
                it.copy(
                    isStartingSession = false,
                    isSessionRunning = true,
                    startedAtEpochMs = startMs,
                    targetEndEpochMs = end,
                    isPaused = paused,
                )
            }
            syncMetrics(application)
            viewModelScope.launch {
                val mode = prefs.lastModeId.first()?.let(VigilMode::fromId)
                _uiState.update { it.copy(runningMode = mode) }
            }
        }
        syncPermissionState(application)
    }

    /**
     * Align UI with the foreground service and persisted flags (e.g. after auto-stop or process death).
     */
    fun syncFromServiceState() {
        val ctx = getApplication<Application>()
        syncPermissionState(ctx)
        if (!VigilSessionService.isRunning(ctx)) {
            if (_uiState.value.isSessionRunning || _uiState.value.isStartingSession) {
                _uiState.update {
                    it.copy(
                        isStartingSession = false,
                        isSessionRunning = false,
                        runningMode = null,
                        startedAtEpochMs = null,
                        targetEndEpochMs = null,
                        isPaused = false,
                    )
                }
            }
            _uiState.update {
                it.copy(
                    isStartingSession = false,
                    metrics = emptyList(),
                    metricsSnapshot = SessionMetricsSnapshot(),
                    alertRingVisible = false,
                )
            }
            return
        }
        val start = VigilSessionService.readSessionStartEpochMs(ctx)
        val end = VigilSessionService.readSessionTargetEndEpochMs(ctx)
        val paused = VigilSessionService.readSessionPaused(ctx)
        viewModelScope.launch {
            val mode = prefs.lastModeId.first()?.let(VigilMode::fromId)
            val metricsSnapshot = VigilSessionService.readMetricsSnapshot(ctx)
            val sensorSdk = PermissionCapabilityCoordinator.sensorSdkAvailability(ctx)
            val androidSensors = PermissionCapabilityCoordinator.androidSensorsAvailability(ctx)
            val healthServices = PermissionCapabilityCoordinator.healthServicesAvailability(ctx)
            _uiState.update {
                it.copy(
                    isStartingSession = false,
                    isSessionRunning = true,
                    runningMode = mode ?: it.runningMode,
                    startedAtEpochMs = start ?: it.startedAtEpochMs,
                    targetEndEpochMs = end,
                    isPaused = paused,
                    metricsSnapshot = metricsSnapshot,
                    metrics =
                        MetricCatalog.fromSnapshot(
                            snapshot = metricsSnapshot,
                            sensorSdk = sensorSdk,
                            androidSensors = androidSensors,
                            healthServices = healthServices,
                        ),
                    alertRingVisible = metricsSnapshot.alertReason != null,
                )
            }
        }
    }

    fun selectMode(mode: VigilMode) {
        _uiState.update { it.copy(selectedMode = mode) }
        viewModelScope.launch { prefs.setLastMode(mode) }
    }

    fun testBuzz() {
        val app = getApplication<Application>()
        val outcome = haptics.playTestBuzz()
        Log.i("Vigil", "testBuzz outcome=$outcome")
        val msg =
            when (outcome) {
                TestBuzzOutcome.Played -> app.getString(R.string.test_buzz_toast_played)
                TestBuzzOutcome.NoVibrator -> app.getString(R.string.test_buzz_toast_no_vibrator)
                TestBuzzOutcome.NoHardware -> app.getString(R.string.test_buzz_toast_no_hardware)
                TestBuzzOutcome.Failed -> app.getString(R.string.test_buzz_toast_failed)
            }
        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
    }

    fun startSession(mode: VigilMode, targetEndEpochMs: Long? = null): Boolean {
        val ctx = getApplication<Application>()
        syncPermissionState(ctx)
        if (!canStartSession()) {
            return false
        }
        _uiState.update {
            it.copy(
                selectedMode = mode,
                runningMode = mode,
                targetEndEpochMs = targetEndEpochMs,
                isStartingSession = true,
                isPaused = false,
            )
        }
        viewModelScope.launch {
            val cooldown = prefs.interventionCooldownMs.first()
            val hapticsOn = _uiState.value.hapticsEnabled
            val startMs = System.currentTimeMillis()
            prefs.setLastMode(mode)
            VigilSessionService.writeSessionStartEpochMs(ctx, startMs)
            VigilSessionService.writeSessionTargetEndEpochMs(ctx, targetEndEpochMs)
            VigilSessionService.writeSessionPaused(ctx, false)
            VigilSessionService.start(ctx, mode, hapticsOn, cooldown, targetEndEpochMs)
            delay(350L)
            if (VigilSessionService.isRunning(ctx)) {
                _uiState.update {
                    it.copy(
                        isStartingSession = false,
                        isSessionRunning = true,
                        startedAtEpochMs = startMs,
                    )
                }
                syncMetrics(ctx)
            } else {
                _uiState.update {
                    it.copy(
                        isStartingSession = false,
                        isSessionRunning = false,
                        startedAtEpochMs = null,
                    )
                }
            }
        }
        return true
    }

    fun canStartSession(): Boolean =
        _uiState.value.hasRequiredPermissions

    fun pauseSession() {
        val ctx = getApplication<Application>()
        if (!VigilSessionService.isRunning(ctx)) return
        VigilSessionService.pause(ctx)
        _uiState.update { it.copy(isPaused = true) }
    }

    fun resumeSession() {
        val ctx = getApplication<Application>()
        if (!VigilSessionService.isRunning(ctx)) return
        VigilSessionService.resume(ctx)
        _uiState.update { it.copy(isPaused = false) }
    }

    fun endSession() {
        val ctx = getApplication<Application>()
        if (VigilSessionService.isRunning(ctx)) {
            VigilSessionService.stop(ctx)
        }
        VigilSessionService.clearSessionPersistence(ctx)
        _uiState.update {
            it.copy(
                isStartingSession = false,
                isSessionRunning = false,
                runningMode = null,
                startedAtEpochMs = null,
                targetEndEpochMs = null,
                isPaused = false,
            )
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setHapticsEnabled(enabled) }
    }

    fun startSpo2Measurement() {
        val ctx = getApplication<Application>()
        if (!VigilSessionService.isRunning(ctx)) return
        VigilSessionService.startSpo2Measurement(ctx)
    }

    fun stopSpo2Measurement() {
        val ctx = getApplication<Application>()
        if (!VigilSessionService.isRunning(ctx)) return
        VigilSessionService.stopSpo2Measurement(ctx)
    }

    private fun syncMetrics(ctx: Application) {
        val metricsSnapshot = VigilSessionService.readMetricsSnapshot(ctx)
        val sensorSdk = PermissionCapabilityCoordinator.sensorSdkAvailability(ctx)
        val androidSensors = PermissionCapabilityCoordinator.androidSensorsAvailability(ctx)
        val healthServices = PermissionCapabilityCoordinator.healthServicesAvailability(ctx)
        _uiState.update {
            it.copy(
                metricsSnapshot = metricsSnapshot,
                metrics =
                    MetricCatalog.fromSnapshot(
                        snapshot = metricsSnapshot,
                        sensorSdk = sensorSdk,
                        androidSensors = androidSensors,
                        healthServices = healthServices,
                    ),
                alertRingVisible = metricsSnapshot.alertReason != null,
            )
        }
    }

    private fun syncPermissionState(ctx: Application) {
        val readiness = PermissionCapabilityCoordinator.sessionPermissionReadiness(ctx)
        _uiState.update {
            it.copy(
                hasRequiredPermissions = readiness.canStartSession,
                missingPermissions = readiness.requiredMissingPermissions.map(::permissionLabel),
                optionalPermissions = readiness.optionalMissingPermissions.map(::permissionLabel),
            )
        }
    }

    private fun permissionLabel(permission: String): String =
        when (permission) {
            Manifest.permission.ACTIVITY_RECOGNITION -> "Activity recognition"
            readHeartRatePermission -> "Heart rate"
            readOxygenSaturationPermission -> "Oxygen saturation"
            Manifest.permission.BODY_SENSORS -> "Body sensors"
            Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
            Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
            else -> permission.substringAfterLast('.')
        }
}
