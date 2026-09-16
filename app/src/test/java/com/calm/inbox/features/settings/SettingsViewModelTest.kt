package com.calm.inbox.features.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.calm.inbox.core.notifications.NotificationAccessMonitor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(temporaryFolder.root, "viewmodel-settings.preferences_pb")
        }
        repository = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun viewModelWithPermission(granted: Boolean): SettingsViewModel =
        SettingsViewModel(
            repository,
            NotificationAccessMonitor(isGranted = { granted }),
            LatencyRecorder(dataStore, scope)
        )

    @Test
    fun exposesInitialSettingsAndPermission() = runTest {
        viewModel = viewModelWithPermission(false)

        assertThat(viewModel.blacklist.value).containsExactly(SettingsRepository.SELF_PACKAGE)
        assertThat(viewModel.noiseThreshold.value).isEqualTo(1)
        assertThat(viewModel.notificationAccessGranted.value).isFalse()
    }

    @Test
    fun addAndRemovePackageDelegateToRepository() = runTest {
        viewModel = viewModelWithPermission(true)
        viewModel.addPackage(" noisy.app ")

        assertThat(viewModel.blacklist.value).containsExactly(
            SettingsRepository.SELF_PACKAGE,
            "noisy.app"
        )

        viewModel.removePackage("noisy.app")
        assertThat(viewModel.blacklist.value).containsExactly(SettingsRepository.SELF_PACKAGE)
    }

    @Test
    fun selfPackageCannotBeRemovedThroughViewModel() = runTest {
        viewModel = viewModelWithPermission(true)
        viewModel.addPackage("noisy.app")
        viewModel.removePackage(SettingsRepository.SELF_PACKAGE)

        assertThat(viewModel.blacklist.value).containsExactly(
            SettingsRepository.SELF_PACKAGE,
            "noisy.app"
        )
    }

    @Test
    fun setNoiseThresholdClampsValue() = runTest {
        viewModel = viewModelWithPermission(true)
        viewModel.setNoiseThreshold(9)

        assertThat(viewModel.noiseThreshold.value).isEqualTo(5)

        viewModel.setNoiseThreshold(0)
        assertThat(viewModel.noiseThreshold.value).isEqualTo(1)
    }

    @Test
    fun permissionStateFollowsMonitor() = runTest {
        var granted = false
        viewModel = SettingsViewModel(
            repository,
            NotificationAccessMonitor(isGranted = { granted }),
            LatencyRecorder(dataStore, scope)
        )

        viewModel.notificationAccessGranted.test {
            assertThat(awaitItem()).isFalse()
            granted = true
            advanceTimeBy(1_000)
            assertThat(awaitItem()).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val testDispatcher: kotlinx.coroutines.test.TestDispatcher
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
