package com.calm.inbox.features.brief

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object BriefScheduler {

    /** 下一个严格未来的 22:00（now == 22:00 整点时排到明天，避免与当日任务重叠）。 */
    fun initialDelayToNext22(now: LocalDateTime): Duration {
        val today22 = now.toLocalDate().atTime(22, 0)
        val target = if (now.isBefore(today22)) today22 else today22.plusDays(1)
        return Duration.between(now, target)
    }

    /** 在 Application.onCreate 调用；KEEP 策略保证重复调用不重置周期。 */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BriefWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayToNext22(LocalDateTime.now()).toMillis(), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BriefWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
