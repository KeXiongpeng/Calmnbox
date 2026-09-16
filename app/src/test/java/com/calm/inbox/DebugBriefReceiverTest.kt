package com.calm.inbox

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.calm.inbox.features.brief.BriefWorker
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugBriefReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = SuccessWorker(appContext, workerParameters)
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun matchingActionEnqueuesOneTimeBriefWorker() {
        DebugBriefReceiver().onReceive(
            context,
            Intent(DebugBriefReceiver.ACTION_GENERATE_BRIEF)
        )

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(DebugBriefReceiver.UNIQUE_WORK_NAME)
            .get()

        assertThat(workInfos).hasSize(1)
        assertThat(workInfos.single().state).isEqualTo(androidx.work.WorkInfo.State.SUCCEEDED)
        assertThat(workInfos.single().tags).contains(BriefWorker::class.java.name)
    }

    @Test
    fun unknownActionDoesNotEnqueueWork() {
        DebugBriefReceiver().onReceive(context, Intent("com.calm.inbox.UNKNOWN_ACTION"))

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(DebugBriefReceiver.UNIQUE_WORK_NAME)
            .get()

        assertThat(workInfos).isEmpty()
    }

    private class SuccessWorker(
        appContext: Context,
        workerParameters: WorkerParameters,
    ) : ListenableWorker(appContext, workerParameters) {
        override fun startWork(): ListenableFuture<Result> =
            Futures.immediateFuture(Result.success())
    }
}
