package com.vigil.wear.session

import com.vigil.wear.metrics.MetricReading

/**
 * UI-facing session state. [SessionViewModel] exposes this via [kotlinx.coroutines.flow.StateFlow]
 * so Compose can subscribe with [androidx.compose.runtime.collectAsState].
 */
data class SessionUiState(
    val selectedMode: VigilMode? = null,
    val isStartingSession: Boolean = false,
    val isSessionRunning: Boolean = false,
    val runningMode: VigilMode? = null,
    val startedAtEpochMs: Long? = null,
    /** Wall-clock time when the session should auto-stop; null = until user stops. */
    val targetEndEpochMs: Long? = null,
    val isPaused: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val hasRequiredPermissions: Boolean = false,
    val missingPermissions: List<String> = emptyList(),
    val optionalPermissions: List<String> = emptyList(),
    val metrics: List<MetricReading> = emptyList(),
    val metricsSnapshot: SessionMetricsSnapshot = SessionMetricsSnapshot(),
    val alertRingVisible: Boolean = false,
)
