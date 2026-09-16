package com.calm.inbox.features.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.calm.inbox.core.model.DownloadState
import com.calm.inbox.core.model.ModelManager
import com.calm.inbox.core.notifications.NotificationAccessMonitor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    private fun viewModelWithPermission(
        granted: Boolean,
        modelManager: FakeModelManager = FakeModelManager()
    ): SettingsViewModel = SettingsViewModel(
        repository,
        NotificationAccessMonitor(isGranted = { granted }),
        LatencyRecorder(dataStore, scope),
        modelManager
    )



    @Test
    fun modelStateReflectsInitialReadiness() = runTest {
        val viewModel = viewModelWithPermission(
            granted = true,
            modelManager = FakeModelManager(initiallyReady = true)
        )

        assertThat(viewModel.modelState.value.isReady).isTrue()
        assertThat(viewModel.modelState.value.isDownloading).isFalse()
    }

    @Test
    fun downloadModelEmitsProgressAndCompletes() = runTest {
        val modelManager = FakeModelManager(
            initiallyReady = false,
            downloadStates = listOf(
                DownloadState.Downloading(0.25f),
                DownloadState.Downloading(0.75f),
                DownloadState.Done(File("/models/qwen"))
            )
        )
        val viewModel = viewModelWithPermission(true, modelManager)

        viewModel.downloadModel()

        assertThat(modelManager.downloadCalls).isEqualTo(1)
        assertThat(viewModel.modelState.value.isReady).isTrue()
        assertThat(viewModel.modelState.value.isDownloading).isFalse()
        assertThat(viewModel.modelState.value.progress).isEqualTo(1f)
        assertThat(viewModel.modelState.value.error).isNull()
    }

    @Test
    fun downloadFailureStopsProgressAndShowsMessage() = runTest {
        val modelManager = FakeModelManager(
            downloadStates = listOf(
                DownloadState.Downloading(0.4f),
                DownloadState.Failed("network unavailable")
            )
        )
        val viewModel = viewModelWithPermission(true, modelManager)

        viewModel.downloadModel()

        assertThat(viewModel.modelState.value.isDownloading).isFalse()
        assertThat(viewModel.modelState.value.isReady).isFalse()
        assertThat(viewModel.modelState.value.error).isEqualTo("network unavailable")
    }

    @Test
    fun deleteModelUpdatesReadiness() = runTest {
        val modelManager = FakeModelManager(initiallyReady = true)
        val viewModel = viewModelWithPermission(true, modelManager)

        viewModel.deleteModel()

        assertThat(modelManager.deleteCalls).isEqualTo(1)
        assertThat(viewModel.modelState.value.isReady).isFalse()
        assertThat(viewModel.modelState.value.isDownloading).isFalse()
    }

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
            LatencyRecorder(dataStore, scope),
            FakeModelManager()
        )

        viewModel.notificationAccessGranted.test {
            assertThat(awaitItem()).isFalse()
            granted = true
            advanceTimeBy(1_000)
            assertThat(awaitItem()).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }
    private class FakeModelManager(
        private val initiallyReady: Boolean = false,
        private val downloadStates: List<DownloadState> = listOf(
            DownloadState.Done(File("/models/qwen"))
        )
    ) : ModelManager(ApplicationProvider.getApplicationContext(), object : com.calm.inbox.core.model.Downloader {
        override fun download(url: String, dest: File): Flow<DownloadState> = flow { }
    }) {
        private var ready = initiallyReady
        var downloadCalls = 0
            private set
        var deleteCalls = 0
            private set

        override fun isModelReady(): Boolean = ready

        override fun downloadModel(): Flow<DownloadState> {
            downloadCalls++
            return flow {
                downloadStates.forEach { emit(it) }
                if (downloadStates.lastOrNull { it is DownloadState.Done } != null) {
                    ready = true
                }
            }
        }

        override suspend fun deleteModel() {
            deleteCalls++
            ready = false
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
