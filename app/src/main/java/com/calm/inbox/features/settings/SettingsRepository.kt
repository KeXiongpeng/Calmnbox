package com.calm.inbox.features.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val blacklist: Flow<Set<String>> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            (preferences[BLACKLIST_KEY] ?: emptySet()) + SELF_PACKAGE
        }

    val noiseThreshold: Flow<Int> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[NOISE_THRESHOLD_KEY] ?: DEFAULT_NOISE_THRESHOLD
        }

    suspend fun addBlacklist(pkg: String) {
        val normalized = pkg.trim()
        if (normalized.isEmpty()) return
        dataStore.edit { preferences ->
            preferences[BLACKLIST_KEY] = (preferences[BLACKLIST_KEY] ?: emptySet()) + normalized
        }
    }

    suspend fun removeBlacklist(pkg: String) {
        if (pkg == SELF_PACKAGE) return
        dataStore.edit { preferences ->
            preferences[BLACKLIST_KEY] = (preferences[BLACKLIST_KEY] ?: emptySet()) - pkg
        }
    }

    suspend fun setNoiseThreshold(value: Int) {
        dataStore.edit { preferences ->
            preferences[NOISE_THRESHOLD_KEY] = value.coerceIn(1, 5)
        }
    }

    companion object {
        const val SELF_PACKAGE = "com.calm.inbox"
        const val DEFAULT_NOISE_THRESHOLD = 1
        private val BLACKLIST_KEY = stringSetPreferencesKey("blacklist")
        private val NOISE_THRESHOLD_KEY = intPreferencesKey("noise_threshold")
    }
}
