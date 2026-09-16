package com.calm.inbox.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.calm.inbox.features.settings.LatencyRecorder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BenchmarkModule {

    @Provides
    @Singleton
    fun provideLatencyRecorder(dataStore: DataStore<Preferences>): LatencyRecorder =
        LatencyRecorder(dataStore, CoroutineScope(SupervisorJob() + Dispatchers.IO))
}
