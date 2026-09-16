package com.calm.inbox.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.calm.inbox.core.classify.NotificationClassifierApplier
import com.calm.inbox.core.classify.RuleEngine
import com.calm.inbox.core.database.AppDatabase
import com.calm.inbox.core.database.dao.BriefDao
import com.calm.inbox.core.database.dao.ChatMessageDao
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.model.EngineHolder
import com.calm.inbox.core.model.LlmEngine
import com.calm.inbox.core.model.ModelManager
import com.calm.inbox.core.notifications.NotificationAccessMonitor
import com.calm.inbox.core.notifications.NotificationEntityFactory
import com.calm.inbox.features.chat.EngineReadiness
import com.calm.inbox.features.settings.NotificationAccessChecker
import com.calm.inbox.features.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    "calm_settings"
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "calm_inbox.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides
    @Singleton
    fun provideSettingsRepository(dataStore: DataStore<Preferences>): SettingsRepository =
        SettingsRepository(dataStore)

    @Provides
    @Singleton
    fun provideNotificationAccessChecker(@ApplicationContext context: Context): NotificationAccessChecker =
        NotificationAccessChecker(context)

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()

    @Provides
    @Singleton
    fun provideNotificationAccessMonitor(
        checker: NotificationAccessChecker
    ): NotificationAccessMonitor = NotificationAccessMonitor(checker::isGranted)

    @Provides
    @Singleton
    fun provideEngineReadiness(
        modelManager: ModelManager,
        engine: LlmEngine,
        holder: EngineHolder
    ): EngineReadiness = EngineReadiness(
        isModelReady = modelManager::isModelReady,
        modelPath = { modelManager.modelDir().absolutePath },
        engine = engine,
        holder = holder
    )

    @Provides
    @Singleton
    fun provideNotificationEntityFactory(clock: Clock): NotificationEntityFactory =
        NotificationEntityFactory(clock)

    @Provides
    @Singleton
    fun provideRuleEngine(): RuleEngine = RuleEngine()

    @Provides
    @Singleton
    fun provideNotificationClassifierApplier(
        dao: NotificationDao,
        rules: RuleEngine
    ): NotificationClassifierApplier = NotificationClassifierApplier(dao, rules)

    @Provides
    fun provideNotificationDao(database: AppDatabase): NotificationDao =
        database.notificationDao()

    @Provides
    fun provideBriefDao(database: AppDatabase): BriefDao = database.briefDao()

    @Provides
    fun provideChatMessageDao(database: AppDatabase): ChatMessageDao =
        database.chatMessageDao()
}
