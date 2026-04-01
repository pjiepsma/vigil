package com.vigil.wear

import android.app.Application
import com.vigil.wear.data.VigilPreferences

/**
 * Provides a single [VigilPreferences] instance (DataStore) for the whole process.
 * Registered in the manifest as `android:name`.
 */
class VigilApplication : Application() {

    lateinit var preferences: VigilPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = VigilPreferences(this)
    }
}
