package com.calm.inbox.core.notifications

import com.calm.inbox.core.database.entity.NotificationEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class NotificationEntityFactoryTest {

    private val clock = Clock.fixed(Instant.ofEpochMilli(777L), ZoneOffset.UTC)
    private val factory = NotificationEntityFactory(clock)

    private fun posted(
        packageName: String = "com.example.app",
        title: String = "Title",
        text: String = "Text",
        postedAt: Long = 123L
    ) = PostedNotification(
        packageName = packageName,
        appName = "Example",
        title = title,
        text = text,
        postedAt = postedAt
    )

    @Test
    fun blacklistedAndSelfPackagesReturnNull() {
        assertThat(factory.create(posted("blocked.app"), setOf("blocked.app"))).isNull()
        assertThat(factory.create(posted("com.calm.inbox"), emptySet())).isNull()
    }

    @Test
    fun createBuildsCompleteNotificationEntity() {
        val entity = factory.create(posted(), setOf("blocked.app"))

        val expected = NotificationEntity(
            packageName = "com.example.app",
            appName = "Example",
            title = "Title",
            text = "Text",
            postedAt = 123L,
            digest = NotificationFilter.digest("com.example.app", 123L, "Title")
        )
        assertThat(entity).isEqualTo(expected)
    }

    @Test
    fun createTruncatesTextToOneHundredCharacters() {
        val entity = factory.create(posted(text = "a".repeat(180)), emptySet())

        assertThat(entity?.text?.length).isEqualTo(100)
    }

    @Test
    fun zeroAndNegativePostTimeFallBackToClockMillis() {
        assertThat(factory.create(posted(postedAt = 0L), emptySet())?.postedAt).isEqualTo(777L)
        assertThat(factory.create(posted(postedAt = -1L), emptySet())?.postedAt).isEqualTo(777L)
    }
}
