package com.calm.inbox.features.chat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calm.inbox.core.database.AppDatabase
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.NotificationEntity
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationRetrieverTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NotificationDao
    private lateinit var retriever: NotificationRetriever

    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 16, 15, 30)
    private val today: Long
        get() = millis(LocalDateTime.of(2026, 9, 16, 10, 0))
    private val yesterday: Long
        get() = millis(LocalDateTime.of(2026, 9, 15, 10, 0))
    private val defaultRange: TimeRange
        get() = TimeQueryParser.fullDay(now.toLocalDate())

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.notificationDao()
        retriever = NotificationRetriever(dao)
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(originalTimeZone)
    }

    private fun millis(value: LocalDateTime): Long =
        value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun item(
        id: Long,
        title: String,
        text: String,
        postedAt: Long,
    ) = NotificationEntity(
        id = id,
        packageName = "com.example.app",
        appName = "Example",
        title = title,
        text = text,
        postedAt = postedAt,
        digest = "digest-$id"
    )

    @Test
    fun defaultRangeOnlySearchesToday() = runTest {
        dao.insert(item(1, "昨天的快递", "已送达", yesterday))
        dao.insert(item(2, "今天的快递", "运输中", today))

        val result = retriever.search("快递到哪了", now, defaultRange)

        assertThat(result.map { it.id }).containsExactly(2L)
    }

    @Test
    fun searchCombinesTimeAndKeyword() = runTest {
        dao.insert(item(1, "验证码", "123456", today))
        dao.insert(item(2, "快递", "验证码 998", yesterday))

        val result = retriever.search("昨天的验证码是多少", now)

        assertThat(result.map { it.id }).containsExactly(2L)
    }

    @Test
    fun multiKeywordResultsAreDeduplicatedAndSortedNewestFirst() = runTest {
        dao.insert(item(1, "快递外卖", "午餐", today - 1))
        dao.insert(item(2, "外卖账单", "午餐", today))
        dao.insert(item(3, "快递", "已送达", today + 1))

        val result = retriever.search("快递 外卖 账单", now, defaultRange)

        assertThat(result.map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test
    fun searchReturnsAtMostFiftyResults() = runTest {
        repeat(55) { index ->
            val id = index + 1L
            dao.insert(item(id, "快递", "包裹 $id", today + id))
        }

        val result = retriever.search("快递", now, defaultRange)

        assertThat(result).hasSize(50)
        assertThat(result.first().id).isEqualTo(55L)
    }

    @Test
    fun questionWithoutKeywordsReturnsEmptyList() = runTest {
        dao.insert(item(1, "快递", "已送达", today))

        val result = retriever.search("今天我的", now, defaultRange)

        assertThat(result).isEmpty()
    }

    @Test
    fun likeSearchNormalizesLatinCaseWithoutNetwork() = runTest {
        dao.insert(item(1, "ORDER", "shipped", today))
        dao.insert(item(2, "Invoice", "unrelated", today))

        val result = retriever.search("Where is JD order", now, defaultRange)

        assertThat(result.map { it.id }).containsExactly(1L)
    }
}
