package com.calm.inbox.di

import com.calm.inbox.core.classify.ClassificationQueue
import com.calm.inbox.core.classify.HybridClassifier
import com.calm.inbox.core.classify.RuleEngine
import com.calm.inbox.core.database.dao.NotificationDao
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
object ClassifyModule {

    @Provides
    @Singleton
    fun provideHybridClassifier(rules: RuleEngine, engine: dagger.Lazy<com.calm.inbox.core.model.LlmEngine>): HybridClassifier =
        HybridClassifier(rules, engine.get())

    @Provides
    @Singleton
    fun provideClassificationQueue(
        classifier: HybridClassifier,
        dao: NotificationDao,
    ): ClassificationQueue = ClassificationQueue(
        classifier = classifier,
        dao = dao,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}
