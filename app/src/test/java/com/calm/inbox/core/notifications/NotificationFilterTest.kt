package com.calm.inbox.core.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest

class NotificationFilterTest {

    @Test
    fun digestUsesSha256OfPackageNamePostedAtAndTitle() {
        val actual = NotificationFilter.digest("com.example", 123L, "Title")
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("com.example|123|Title".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun truncateKeepsAtMostOneHundredCharacters() {
        assertThat(NotificationFilter.truncate("a".repeat(180), 100).length).isEqualTo(100)
    }

    @Test
    fun negativeMaxBecomesZero() {
        assertThat(NotificationFilter.truncate("abc", -1)).isEmpty()
    }

    @Test
    fun blacklistRejectsPackageAndSelfIsAlwaysRejected() {
        assertThat(NotificationFilter(setOf("noisy.app")).shouldAccept("noisy.app")).isFalse()
        assertThat(NotificationFilter(setOf("com.calm.inbox")).shouldAccept("com.calm.inbox")).isFalse()
        assertThat(NotificationFilter(emptySet()).shouldAccept("com.calm.inbox")).isFalse()
        assertThat(NotificationFilter(setOf("noisy.app")).shouldAccept("normal.app")).isTrue()
        assertThat(NotificationFilter(emptySet()).shouldAccept("  ")).isFalse()
    }
}
