package com.vigil.wear.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vigil.wear.session.VigilMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Small wrapper around DataStore Preferences for user toggles and last-used mode.
 * DataStore is Android's modern replacement for SharedPreferences — reads are asynchronous [Flow]s.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vigil_prefs")

class VigilPreferences(private val context: Context) {

    private object Keys {
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val LAST_MODE_ID = stringPreferencesKey("last_mode_id")
        val COOLDOWN_MS = longPreferencesKey("intervention_cooldown_ms")
    }

    /** Default cooldown between intervention buzzes; tunable later from a settings screen. */
    companion object {
        const val DEFAULT_COOLDOWN_MS = 45_000L
    }

    val hapticsEnabled: Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.HAPTICS_ENABLED] ?: true
        }

    val lastModeId: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_MODE_ID] }

    val interventionCooldownMs: Flow<Long> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.COOLDOWN_MS] ?: DEFAULT_COOLDOWN_MS
        }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HAPTICS_ENABLED] = enabled }
    }

    suspend fun setLastMode(mode: VigilMode) {
        context.dataStore.edit { it[Keys.LAST_MODE_ID] = mode.id }
    }

    suspend fun setCooldownMs(ms: Long) {
        context.dataStore.edit { it[Keys.COOLDOWN_MS] = ms }
    }
}
