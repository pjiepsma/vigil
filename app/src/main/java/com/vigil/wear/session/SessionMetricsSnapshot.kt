package com.vigil.wear.session

import com.vigil.wear.classifier.AppUserState
import com.vigil.wear.classifier.ModeAlignment

data class SessionMetricsSnapshot(
    val heartRateBpm: Int? = null,
    val hrvRmssdMs: Float? = null,
    val ppgGreenActive: Boolean? = null,
    val spo2Pct: Int? = null,
    val skinTempC: Float? = null,
    val accelerometerMagnitudeG: Float? = null,
    val posture: String? = null,
    val barometerHpa: Float? = null,
    val speedMs: Float? = null,
    val cadenceSpm: Float? = null,
    val elevationChangeMpm: Float? = null,
    val platformUserState: String? = null,
    val gpsActive: Boolean? = null,
    val fallDetected: Boolean? = null,
    val classifiedState: AppUserState? = null,
    val modeAlignment: ModeAlignment? = null,
    val confidence: Float? = null,
    val alertReason: String? = null,
    val reasons: List<String> = emptyList(),
)
