package com.calm.inbox.features.inbox

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calm.inbox.core.database.AppDatabase
import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.features.settings.SettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
class InboxViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: InboxViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(temporaryFolder.root, "inbox-settings.preferences_pb")
        }
        repository = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
        Dispatchers.resetMain()
    }

    private suspend fun insert(
        category: String,
        importance: Int,
        postedAt: Long,
        title: String,
        digest: String
    ): Long = database.notificationDao().insert(
        NotificationEntity(
            packageName = "com.example.$digest",
            appName = "App $digest",
            title = title,
            text = "Text $digest",
            postedAt = postedAt,
            category = category,
            importance = importance,
            summary = title,
            digest = digest
        )
    )

    @Test
    fun partitionsImportantAndNoiseUsingThresholdAndMarketingRule() = runTest {
        val verification = insert("VERIFICATION", 5, 30L, "验证码", "verification")
        val shopping = insert("SHOPPING", 2, 20L, "购物", "shopping")
        insert("MARKETING", 4, 10L, "营销", "marketing")
        insert("SYSTEM", 1, 15L, "系统", "system")
        repository.setNoiseThreshold(1)
        viewModel = InboxViewModel(database.notificationDao(), repository)

        val state = viewModel.state.first { !it.isLoading }

        assertThat(state.important.map { it.id }).containsExactly(verification, shopping).inOrder()
        assertThat(state.noiseGroups.map { it.category }).containsExactly("SYSTEM", "MARKETING").inOrder()
        assertThat(state.noiseGroups.single { it.category == "MARKETING" }.count).isEqualTo(1)
        assertThat(state.noiseGroups.single { it.category == "SYSTEM" }.latestTitle).isEqualTo("系统")
    }

    @Test
    fun toggleNoiseExpandsAndCollapsesCategory() = runTest {
        insert("MARKETING", 1, 10L, "营销", "marketing")
        viewModel = InboxViewModel(database.notificationDao(), repository)
        viewModel.state.first { !it.isLoading }

        viewModel.toggleNoise("MARKETING")
        assertThat(viewModel.state.first().expandedNoiseCategories).containsExactly("MARKETING")

        viewModel.toggleNoise("MARKETING")
        assertThat(viewModel.state.first().expandedNoiseCategories).isEmpty()
    }

    @Test
    fun selectChangesOnlyVisibleUiFilter() = runTest {
        val verification = insert("VERIFICATION", 5, 30L, "验证码", "verification")
        insert("MARKETING", 1, 10L, "营销", "marketing")
        viewModel = InboxViewModel(database.notificationDao(), repository)
        viewModel.state.first { !it.isLoading }

        viewModel.select(InboxFilter.NOISY)

        val state = viewModel.state.first()
        assertThat(state.selectedFilter).isEqualTo(InboxFilter.NOISY)
        assertThat(state.important).isEmpty()
        assertThat(state.noiseGroups.map { it.category }).containsExactly("MARKETING")
        assertThat(database.notificationDao().observeAll().first()).hasSize(2)

        viewModel.select(InboxFilter.IMPORTANT)
        assertThat(viewModel.state.first().important.map { it.id }).containsExactly(verification)
        assertThat(viewModel.state.first().noiseGroups).isEmpty()
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
