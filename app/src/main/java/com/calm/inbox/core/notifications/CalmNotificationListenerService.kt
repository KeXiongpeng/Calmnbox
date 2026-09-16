package com.calm.inbox.core.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.calm.inbox.core.classify.NotificationClassifierApplier
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.features.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CalmNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var dao: NotificationDao
    @Inject lateinit var factory: NotificationEntityFactory
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var classifierApplier: NotificationClassifierApplier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val title = notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()
        val text = notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            .orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val posted = PostedNotification(
            packageName = sbn.packageName,
            appName = appNameOf(sbn.packageName),
            title = title,
            text = text,
            postedAt = sbn.postTime
        )

        scope.launch {
            val blacklist = settingsRepository.blacklist.first()
            val entity = factory.create(
                item = posted,
                blacklist = blacklist
            ) ?: return@launch
            val rowId = dao.insert(entity)
            if (rowId != -1L) {
                classifierApplier.classifyPending(1)
            }
            // W2 Task 11 appends ClassificationQueue.offer(entity.copy(id = rowId)) here.
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun appNameOf(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    }.getOrDefault(packageName)
}
