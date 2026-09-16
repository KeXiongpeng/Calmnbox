package com.calm.inbox.di

import android.content.Context
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.model.LlmEngine
import com.calm.inbox.core.notifications.BriefNotifier
import com.calm.inbox.features.brief.BriefGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BriefModule {

    @Provides
    @Singleton
    fun provideBriefGenerator(
        engine: dagger.Lazy<LlmEngine>,
        dao: NotificationDao,
        clock: Clock,
    ): BriefGenerator = BriefGenerator(engine.get(), dao, clock)

    @Provides
    @Singleton
    fun provideBriefNotifier(@ApplicationContext context: Context): BriefNotifier =
        BriefNotifier(context)
}
