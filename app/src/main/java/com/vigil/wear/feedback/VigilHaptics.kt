package com.vigil.wear.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat

enum class TestBuzzOutcome {
    Played,
    NoVibrator,
    NoHardware,
    Failed,
}

/**
 * Central place for intervention vibration. Keeps [android.os.Vibrator] details out of UI and ViewModel.
 * Wear OS maps this to the watch motor — emulators may not buzz; test on hardware when possible.
 */
class VigilHaptics(context: Context) {

    private val appContext = context.applicationContext
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = appContext.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.getSystemService(appContext, Vibrator::class.java)
        }

    /** Short double-pulse pattern so the user can tell Vigil from a generic notification tick. */
    fun playInterventionBuzz() {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect =
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 80, 120, 80),
                        intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE),
                        -1,
                    )
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 80, 120, 80), -1)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "playInterventionBuzz failed", t)
        }
    }

    /**
     * Lighter tap for Settings "Test buzz".
     * Avoids [VibrationEffect.createPredefined] (often unsupported on emulators → crash).
     * Returns an outcome for UI feedback; never throws.
     */
    fun playTestBuzz(): TestBuzzOutcome {
        val v = vibrator ?: run {
            Log.w(TAG, "playTestBuzz: no Vibrator (null)")
            return TestBuzzOutcome.NoVibrator
        }
        @Suppress("DEPRECATION")
        if (!v.hasVibrator()) {
            Log.w(TAG, "playTestBuzz: hasVibrator() false")
            return TestBuzzOutcome.NoHardware
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(55, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(55)
            }
            Log.i(TAG, "playTestBuzz: vibrate invoked")
            TestBuzzOutcome.Played
        } catch (t: Throwable) {
            Log.w(TAG, "playTestBuzz: vibrate failed", t)
            TestBuzzOutcome.Failed
        }
    }

    private companion object {
        private const val TAG = "VigilHaptics"
    }
}
