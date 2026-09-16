package com.calm.inbox.core.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.calm.inbox.core.database.dao.NotificationDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CalmNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var dao: NotificationDao
    @Inject lateinit var factory: NotificationEntityFactory

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
            val entity = factory.create(
                item = posted,
                blacklist = setOf(NotificationFilter.SELF_PACKAGE)
            ) ?: return@launch
            val rowId = dao.insert(entity)
            // W2 Task 11 appends ClassificationQueue.offer(entity.copy(id = rowId)) here.
            check(rowId != -1L)
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
