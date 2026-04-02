package com.vigil.wear.classifier

import com.vigil.wear.session.VigilMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class AppUserState(val id: String) {
    Sleeping("sleeping"),
    LyingStill("lying_still"),
    Sitting("sitting"),
    IdleStanding("idle_standing"),
    Walking("walking"),
    Active("active"),
}

data class ClassifierInput(
    val heartRateBpm: Float? = null,
    val hrvRmssdMs: Float? = null,
    val accelerometerMagnitudeG: Float? = null,
    val speedMs: Float? = null,
    val cadenceSpm: Float? = null,
    val skinTempC: Float? = null,
    val spo2Pct: Float? = null,
    val elevationChangeMpm: Float? = null,
    val postureHorizontal: Boolean? = null,
    val stillForMs: Long? = null,
)

data class ClassifierResult(
    val state: AppUserState,
    val runnerUpState: AppUserState,
    val stateScores: Map<AppUserState, Float>,
    val confidence: Float,
    val reasons: List<String>,
    val modeAlignment: ModeAlignment,
    val alertReason: String?,
)

enum class ModeAlignment {
    OnTarget,
    Drifting,
    Mismatched,
}

private data class Range(val min: Float, val max: Float)

private data class StateThresholds(
    val heartRate: Range? = null,
    val hrv: Range? = null,
    val accel: Range? = null,
    val speed: Range? = null,
    val cadence: Range? = null,
    val skinTemp: Range? = null,
    val spo2: Range? = null,
    val elevationTrend: Range? = null,
    val expectsHorizontal: Boolean? = null,
)

object UserStateClassifier {
    private val weights =
        mapOf(
            "heartRate" to 1.0f,
            "hrv" to 0.8f,
            "accel" to 1.0f,
            "speed" to 1.0f,
            "cadence" to 1.0f,
            "skinTemp" to 0.4f,
            "spo2" to 0.4f,
            "elevationTrend" to 0.6f,
            "posture" to 0.7f,
        )

    private val thresholds =
        mapOf(
            AppUserState.Sleeping to
                StateThresholds(
                    heartRate = Range(42f, 62f),
                    hrv = Range(70f, 120f),
                    accel = Range(0f, 0.015f),
                    speed = Range(0f, 0.05f),
                    cadence = Range(0f, 2f),
                    skinTemp = Range(35.2f, 36.2f),
                    spo2 = Range(92f, 97f),
                    elevationTrend = Range(0f, 0.5f),
                    expectsHorizontal = true,
                ),
            AppUserState.LyingStill to
                StateThresholds(
                    heartRate = Range(55f, 72f),
                    hrv = Range(60f, 95f),
                    accel = Range(0f, 0.025f),
                    speed = Range(0f, 0.05f),
                    cadence = Range(0f, 2f),
                    skinTemp = Range(35.8f, 36.5f),
                    spo2 = Range(96f, 99f),
                    elevationTrend = Range(0f, 0.5f),
                    expectsHorizontal = true,
                ),
            AppUserState.Sitting to
                StateThresholds(
                    heartRate = Range(58f, 78f),
                    hrv = Range(55f, 82f),
                    accel = Range(0f, 0.04f),
                    speed = Range(0f, 0.10f),
                    cadence = Range(0f, 5f),
                    skinTemp = Range(36.0f, 36.7f),
                    spo2 = Range(97f, 100f),
                    elevationTrend = Range(0f, 1f),
                    expectsHorizontal = false,
                ),
            AppUserState.IdleStanding to
                StateThresholds(
                    heartRate = Range(60f, 86f),
                    hrv = Range(44f, 76f),
                    accel = Range(0.01f, 0.2f),
                    speed = Range(0f, 0.2f),
                    cadence = Range(0f, 10f),
                    skinTemp = Range(36.2f, 36.9f),
                    spo2 = Range(97f, 100f),
                    elevationTrend = Range(0f, 2f),
                    expectsHorizontal = false,
                ),
            AppUserState.Walking to
                StateThresholds(
                    heartRate = Range(90f, 140f),
                    hrv = Range(26f, 52f),
                    accel = Range(0.25f, 0.75f),
                    speed = Range(0.8f, 2.2f),
                    cadence = Range(70f, 125f),
                    skinTemp = Range(36.5f, 37.3f),
                    spo2 = Range(97f, 100f),
                    elevationTrend = Range(0f, 15f),
                    expectsHorizontal = false,
                ),
            AppUserState.Active to
                StateThresholds(
                    heartRate = Range(120f, 185f),
                    hrv = Range(6f, 38f),
                    accel = Range(0.4f, 3f),
                    speed = Range(0.7f, 5f),
                    cadence = Range(30f, 200f),
                    skinTemp = Range(36.5f, 38.5f),
                    spo2 = Range(93f, 99f),
                    elevationTrend = Range(0f, 40f),
                    expectsHorizontal = false,
                ),
        )

    fun classify(input: ClassifierInput, runningMode: VigilMode): ClassifierResult {
        val scoreByState = AppUserState.values().associateWith { state -> score(state, input) }
        val sorted =
            scoreByState.entries
                .sortedWith(
                    compareByDescending<Map.Entry<AppUserState, Float>> { it.value }
                        .thenBy { energyRank(it.key) },
                )
        val best = sorted.first().key
        val second = sorted.getOrElse(1) { sorted.first() }.key

        // Sleep promotion when prolonged horizontal stillness and low HR persist.
        val promotedBest =
            if (best == AppUserState.LyingStill &&
                input.postureHorizontal == true &&
                input.heartRateBpm != null &&
                input.heartRateBpm <= 62f &&
                (input.stillForMs ?: 0L) >= 10 * 60_000L
            ) {
                AppUserState.Sleeping
            } else {
                best
            }

        val confidence = confidence(scoreByState[promotedBest] ?: 0f, scoreByState[second] ?: 0f)
        val alignment = modeAlignment(promotedBest, runningMode)
        val reasons = reasons(promotedBest, input)
        val alertReason = alertReason(promotedBest, runningMode)

        return ClassifierResult(
            state = promotedBest,
            runnerUpState = second,
            stateScores = scoreByState,
            confidence = confidence,
            reasons = reasons,
            modeAlignment = alignment,
            alertReason = alertReason,
        )
    }

    private fun score(state: AppUserState, input: ClassifierInput): Float {
        val t = thresholds.getValue(state)
        var total = 0f
        var weightTotal = 0f

        fun add(value: Float?, range: Range?, weightKey: String) {
            val weight = weights.getValue(weightKey)
            if (value == null || range == null) return
            total += weight * closeness(value, range)
            weightTotal += weight
        }

        add(input.heartRateBpm, t.heartRate, "heartRate")
        add(input.hrvRmssdMs, t.hrv, "hrv")
        add(input.accelerometerMagnitudeG, t.accel, "accel")
        add(input.speedMs, t.speed, "speed")
        add(input.cadenceSpm, t.cadence, "cadence")
        add(input.skinTempC, t.skinTemp, "skinTemp")
        add(input.spo2Pct, t.spo2, "spo2")
        add(input.elevationChangeMpm, t.elevationTrend, "elevationTrend")

        if (input.postureHorizontal != null && t.expectsHorizontal != null) {
            val w = weights.getValue("posture")
            total += if (input.postureHorizontal == t.expectsHorizontal) w else 0f
            weightTotal += w
        }
        return if (weightTotal <= 0f) 0f else total / weightTotal
    }

    private fun closeness(value: Float, range: Range): Float {
        if (value in range.min..range.max) return 1f
        val center = (range.min + range.max) / 2f
        val halfSpan = max(0.001f, (range.max - range.min) / 2f)
        val dist = abs(value - center)
        return max(0f, 1f - (dist / (halfSpan * 3f)))
    }

    private fun energyRank(state: AppUserState): Int =
        when (state) {
            AppUserState.Sleeping -> 0
            AppUserState.LyingStill -> 1
            AppUserState.Sitting -> 2
            AppUserState.IdleStanding -> 3
            AppUserState.Walking -> 4
            AppUserState.Active -> 5
        }

    private fun confidence(best: Float, second: Float): Float {
        val gap = max(0f, best - second)
        return min(1f, best * 0.7f + gap * 0.3f)
    }

    private fun reasons(state: AppUserState, input: ClassifierInput): List<String> {
        val out = mutableListOf<String>()
        if ((input.stillForMs ?: 0L) > 0L) {
            out += "Still for ${(input.stillForMs ?: 0L) / 1000}s"
        }
        input.heartRateBpm?.let { out += "Heart rate ${it.toInt()} bpm" }
        input.speedMs?.let { out += "Speed ${"%.1f".format(it)} m/s" }
        input.cadenceSpm?.let { out += "Cadence ${it.toInt()} spm" }
        input.accelerometerMagnitudeG?.let { out += "Motion ${"%.3f".format(it)} g" }
        if (state == AppUserState.Active) {
            out += "High-energy profile matched"
        }
        return out.take(3)
    }

    private fun modeAlignment(state: AppUserState, mode: VigilMode): ModeAlignment {
        return when (mode) {
            VigilMode.Passive ->
                if (state == AppUserState.LyingStill || state == AppUserState.Sleeping) {
                    ModeAlignment.Mismatched
                } else {
                    ModeAlignment.OnTarget
                }
            VigilMode.Active ->
                if (state == AppUserState.Active) {
                    ModeAlignment.OnTarget
                } else if (state == AppUserState.Walking) {
                    ModeAlignment.Drifting
                } else {
                    ModeAlignment.Mismatched
                }
        }
    }

    private fun alertReason(state: AppUserState, mode: VigilMode): String? {
        return when (mode) {
            VigilMode.Passive ->
                if (state == AppUserState.LyingStill || state == AppUserState.Sleeping) {
                    "Passive drift risk"
                } else {
                    null
                }
            VigilMode.Active ->
                if (state != AppUserState.Active) {
                    "Active drop-off risk"
                } else {
                    null
                }
        }
    }
}
