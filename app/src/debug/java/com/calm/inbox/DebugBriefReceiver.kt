package com.calm.inbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.calm.inbox.features.brief.BriefWorker

/**
 * Debug-only manual trigger for the daily brief pipeline.
 * This source set is not included in release builds.
 */
class DebugBriefReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_GENERATE_BRIEF) return

        val request = OneTimeWorkRequestBuilder<BriefWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        const val ACTION_GENERATE_BRIEF = "com.calm.inbox.DEBUG_GENERATE_BRIEF"
        const val UNIQUE_WORK_NAME = "debug_daily_brief_worker"
    }
}
