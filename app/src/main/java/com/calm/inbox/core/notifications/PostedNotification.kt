package com.calm.inbox.core.notifications

data class PostedNotification(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long
)
