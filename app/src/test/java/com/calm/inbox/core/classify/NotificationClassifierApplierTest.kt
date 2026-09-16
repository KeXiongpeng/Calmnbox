package com.calm.inbox.core.classify

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calm.inbox.core.database.AppDatabase
import com.calm.inbox.core.database.entity.NotificationEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationClassifierApplierTest {

    private lateinit var database: AppDatabase
    private lateinit var applier: NotificationClassifierApplier

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        applier = NotificationClassifierApplier(database.notificationDao(), RuleEngine())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insert(pkg: String, digest: String): Long =
        database.notificationDao().insert(
            NotificationEntity(
                packageName = pkg,
                appName = "App",
                title = "Title",
                text = "Text",
                postedAt = 1_000L,
                digest = digest
            )
        )

    @Test
    fun unknownPackageStaysUnclassified() = runTest {
        insert("unknown.pkg", "unknown")

        val updated = applier.classifyPending()

        assertThat(updated).isEqualTo(0)
        assertThat(database.notificationDao().getUnclassified(10)).hasSize(1)
    }

    @Test
    fun knownPackageIsClassifiedWithRuleResultAndSummary() = runTest {
        val id = insert("com.eg.android.AlipayGphone", "alipay")

        val updated = applier.classifyPending()

        assertThat(updated).isEqualTo(1)
        val saved = database.notificationDao().observeAll().first().single()
        assertThat(saved.id).isEqualTo(id)
        assertThat(saved.category).isEqualTo("FINANCE")
        assertThat(saved.importance).isEqualTo(4)
        assertThat(saved.summary).isEqualTo("Title")
    }

    @Test
    fun unknownOlderNotificationDoesNotConsumeTheUpdateBudget() = runTest {
        insert("unknown.pkg", "unknown")
        val knownId = insert("com.eg.android.AlipayGphone", "alipay")

        val updated = applier.classifyPending(limit = 1)

        assertThat(updated).isEqualTo(1)
        val known = database.notificationDao().observeAll().first()
            .single { it.id == knownId }
        assertThat(known.category).isEqualTo("FINANCE")
        assertThat(database.notificationDao().getUnclassified(10).single().packageName)
            .isEqualTo("unknown.pkg")
    }

    @Test
    fun repeatedCallDoesNotUpdateClassifiedRows() = runTest {
        insert("com.eg.android.AlipayGphone", "alipay")
        applier.classifyPending()

        val second = applier.classifyPending()

        assertThat(second).isEqualTo(0)
        assertThat(database.notificationDao().getUnclassified(10)).isEmpty()
    }
}
