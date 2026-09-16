package com.calm.inbox.di

import android.content.Context
import com.calm.inbox.core.model.Downloader
import com.calm.inbox.core.model.EngineHolder
import com.calm.inbox.core.model.LlmEngine
import com.calm.inbox.core.model.ModelManager
import com.calm.inbox.core.model.MnnLlmEngine
import com.calm.inbox.core.model.OkHttpDownloader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ModelModule {

    @Provides
    @Singleton
    fun provideDownloader(): Downloader = OkHttpDownloader()

    @Provides
    @Singleton
    fun provideLlmEngine(): LlmEngine = MnnLlmEngine()

    @Provides
    @Singleton
    fun provideEngineHolder(engine: LlmEngine): EngineHolder =
        EngineHolder(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Provides
    @Singleton
    fun provideModelManager(
        @ApplicationContext context: Context,
        downloader: Downloader
    ): ModelManager = ModelManager(context, downloader)
}
