package com.calm.inbox.features.brief

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.calm.inbox.core.database.dao.BriefDao
import com.calm.inbox.core.notifications.BriefNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock
import java.time.LocalDate

@HiltWorker
class BriefWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val generator: BriefGenerator,
    private val briefDao: BriefDao,
    private val notifier: BriefNotifier,
    private val clock: Clock,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val date = LocalDate.now(clock)
        return try {
            if (briefDao.getByDate(date.toString()) != null) {
                return Result.success()   // 幂等：退避重试不重复生成与推送
            }
            val brief = generator.generateFor(date)
            briefDao.insert(brief)
            notifier.push(brief)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "daily_brief_worker"
    }
}
