package com.calm.inbox.features.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(temporaryFolder.root, "calm_settings.preferences_pb")
        }
        repository = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun blacklistInitiallyContainsOnlySelfPackage() = runTest {
        assertThat(repository.blacklist.first()).containsExactly(SettingsRepository.SELF_PACKAGE)
    }

    @Test
    fun addBlacklistTrimsDeduplicatesAndIgnoresEmptyPackage() = runTest {
        repository.addBlacklist(" noisy.app ")
        repository.addBlacklist("noisy.app")
        repository.addBlacklist("   ")

        assertThat(repository.blacklist.first()).containsExactly(
            SettingsRepository.SELF_PACKAGE,
            "noisy.app"
        )
    }

    @Test
    fun removeBlacklistCannotRemoveSelfPackage() = runTest {
        repository.addBlacklist("noisy.app")
        repository.removeBlacklist(SettingsRepository.SELF_PACKAGE)

        assertThat(repository.blacklist.first()).containsExactly(
            SettingsRepository.SELF_PACKAGE,
            "noisy.app"
        )

        repository.removeBlacklist("noisy.app")
        assertThat(repository.blacklist.first()).containsExactly(SettingsRepository.SELF_PACKAGE)
    }

    @Test
    fun noiseThresholdDefaultsToOneAndPersistsThree() = runTest {
        assertThat(repository.noiseThreshold.first()).isEqualTo(1)
        repository.setNoiseThreshold(3)
        assertThat(repository.noiseThreshold.first()).isEqualTo(3)
    }

    @Test
    fun noiseThresholdIsCoercedToSupportedRange() = runTest {
        repository.setNoiseThreshold(0)
        assertThat(repository.noiseThreshold.first()).isEqualTo(1)

        repository.setNoiseThreshold(6)
        assertThat(repository.noiseThreshold.first()).isEqualTo(5)
    }

    @Test
    fun blacklistEmitsUpdatesToCollectors() = runTest {
        repository.blacklist.test {
            assertThat(awaitItem()).containsExactly(SettingsRepository.SELF_PACKAGE)
            repository.addBlacklist("noisy.app")
            assertThat(awaitItem()).containsExactly(
                SettingsRepository.SELF_PACKAGE,
                "noisy.app"
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun corruptPreferencesEmitEmptyDefaults() = runTest {
        val key = stringSetPreferencesKey("blacklist")
        val thresholdKey = intPreferencesKey("noise_threshold")
        dataStore.edit { prefs ->
            prefs[key] = setOf("stored.app")
            prefs[thresholdKey] = 4
        }

        assertThat(repository.blacklist.first()).containsExactly(
            SettingsRepository.SELF_PACKAGE,
            "stored.app"
        )
        assertThat(repository.noiseThreshold.first()).isEqualTo(4)
    }
}
