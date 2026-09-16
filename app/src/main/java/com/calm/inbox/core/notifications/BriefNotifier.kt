package com.calm.inbox.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.calm.inbox.core.database.entity.BriefEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 每日简报本地推送。open 便于测试覆写。 */
@Singleton
open class BriefNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    open fun push(brief: BriefEntity) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "每日简报",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("CalmInbox 简报 " + brief.date)
            .setContentText(
                brief.content.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: "今日简报已生成"
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(brief.content.take(500)))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    context.packageManager.getLaunchIntentForPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // targetSdk 33+ 未授予 POST_NOTIFICATIONS：跳过推送，简报在应用内仍可见。
        }
    }

    companion object {
        const val CHANNEL_ID = "daily_brief"
        const val NOTIFICATION_ID = 2001
    }
}
