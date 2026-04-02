package com.vigil.wear.monitoring

import com.vigil.wear.session.VigilMode

/**
 * Tuning knobs per [VigilMode]. Values are starting points — real watches (especially Samsung)
 * may need adjustment after field testing. Larger [stillnessDurationMs] = wait longer before nudging.
 */
data class StillnessParams(
    val stillnessDurationMs: Long,
    /** If variance of acceleration magnitude stays below this, we treat the wrist as "still". */
    val movementVarianceEpsilon: Float,
)

fun stillnessParamsFor(mode: VigilMode): StillnessParams =
    when (mode) {
        VigilMode.Passive ->
            StillnessParams(
                stillnessDurationMs = 90_000L,
                movementVarianceEpsilon = 0.12f,
            )
        VigilMode.Active ->
            StillnessParams(
                stillnessDurationMs = 180_000L,
                movementVarianceEpsilon = 0.18f,
            )
    }
