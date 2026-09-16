package com.calm.inbox.core.notifications

import java.security.MessageDigest

class NotificationFilter(private val blacklist: Set<String>) {

    fun shouldAccept(pkg: String): Boolean =
        pkg.isNotBlank() && pkg != SELF_PACKAGE && pkg !in blacklist

    companion object {
        const val SELF_PACKAGE = "com.calm.inbox"
        const val MAX_TEXT_LENGTH = 100

        fun truncate(text: String, max: Int = MAX_TEXT_LENGTH): String =
            text.take(max.coerceAtLeast(0))

        fun digest(pkg: String, postedAt: Long, title: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$pkg|$postedAt|$title".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
