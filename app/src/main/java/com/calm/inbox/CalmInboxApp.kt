package com.calm.inbox

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.calm.inbox.core.model.LlmEngine
import com.calm.inbox.features.brief.BriefScheduler
import com.calm.inbox.features.settings.LatencyRecorder
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CalmInboxApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var latencyRecorder: LatencyRecorder
    @Inject lateinit var llmEngine: Lazy<LlmEngine>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        BriefScheduler.schedule(this)
        latencyRecorder.attachTo(llmEngine.get())
    }
}
