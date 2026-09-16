package com.calm.inbox.di

import android.content.Context
import com.calm.inbox.core.model.Downloader
import com.calm.inbox.core.model.ModelManager
import com.calm.inbox.core.model.OkHttpDownloader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ModelModule {

    @Provides
    @Singleton
    fun provideDownloader(): Downloader = OkHttpDownloader()

    @Provides
    @Singleton
    fun provideModelManager(
        @ApplicationContext context: Context,
        downloader: Downloader
    ): ModelManager = ModelManager(context, downloader)
}
