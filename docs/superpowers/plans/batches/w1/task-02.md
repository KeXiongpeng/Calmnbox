### Task 2: Room 数据层（三实体三 DAO + AppDatabase）

**前置依赖：** Task 1 的 :app、Room 2.6.1、kapt、Robolectric 与 Hilt 基线已就绪。

## Files

**Create:**
- app/src/main/java/com/calm/inbox/core/database/Category.kt
- app/src/main/java/com/calm/inbox/core/database/entity/NotificationEntity.kt
- app/src/main/java/com/calm/inbox/core/database/entity/BriefEntity.kt
- app/src/main/java/com/calm/inbox/core/database/entity/ChatMessageEntity.kt
- app/src/main/java/com/calm/inbox/core/database/dao/NotificationDao.kt
- app/src/main/java/com/calm/inbox/core/database/dao/BriefDao.kt
- app/src/main/java/com/calm/inbox/core/database/dao/ChatMessageDao.kt
- app/src/main/java/com/calm/inbox/core/database/AppDatabase.kt
- app/src/main/java/com/calm/inbox/di/AppModule.kt
- app/src/test/java/com/calm/inbox/core/database/NotificationDaoTest.kt
- app/src/test/java/com/calm/inbox/core/database/BriefDaoTest.kt
- app/src/test/java/com/calm/inbox/core/database/ChatMessageDaoTest.kt

**Modify:** 无

## Interfaces

**Produces:** Category 十枚举；NotificationEntity、BriefEntity、ChatMessageEntity；三个 DAO；AppDatabase 版本 1。字段、方法签名与 skeleton.md 的共享接口契约逐字一致。

**Consumes:** Task 1 Hilt 与 Room 依赖。

## Step 1: 写失败测试

NotificationDaoTest 核心用例：

~~~kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: NotificationDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.notificationDao()
    }

    @After
    fun tearDown() = db.close()

    private fun item(digest: String, postedAt: Long = 1_000L, title: String = "Title", text: String = "Text") =
        NotificationEntity(packageName = "com.example.app", appName = "Example", title = title, text = text, postedAt = postedAt, digest = digest)

    @Test
    fun insertIgnoresDuplicateDigest() = runBlocking {
        assertThat(dao.insert(item("a"))).isGreaterThan(0L)
        assertThat(dao.insert(item("a"))).isEqualTo(-1L)
        assertThat(dao.observeAll().first()).hasSize(1)
    }

    @Test
    fun searchUsesTimeAndTitleOrText() = runBlocking {
        dao.insert(item("a", postedAt = 10L, title = "验证码", text = "123456"))
        dao.insert(item("b", postedAt = 20L, title = "营销", text = "验证码迟到了"))
        dao.insert(item("c", postedAt = 5L, title = "验证码", text = "old"))
        val result = dao.searchByKeyword("验证码", start = 10L, end = 20L)
        assertThat(result.map { it.digest }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun updateClassificationAndCountCategories() = runBlocking {
        val id = dao.insert(item("a"))
        dao.updateClassification(id, "VERIFICATION", 5, "登录验证码")
        dao.insert(item("b"))
        val saved = dao.observeAll().first().single { it.id == id }
        assertThat(saved.category).isEqualTo("VERIFICATION")
        assertThat(saved.importance).isEqualTo(5)
        assertThat(saved.summary).isEqualTo("登录验证码")
        assertThat(dao.observeCountsByCategory().first()).containsExactly(
            CategoryCount("VERIFICATION", 1),
            CategoryCount("UNCATEGORIZED", 1)
        )
    }
}
~~~

BriefDaoTest 覆盖 insert/getByDate 与 observeAll 按 date 倒序。ChatMessageDaoTest 覆盖 observeAll 按 createdAt 升序、citationIds 字符串持久化、clear 后为空。全部用内存 Room。

## Step 2: 确认失败

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.database.*"
~~~

**Expected:** 编译失败 Unresolved reference AppDatabase / NotificationDao。

## Step 3: 最小实现

按骨架创建三个实体。NotificationDao：

~~~kotlin
@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: NotificationEntity): Long

    @Query("SELECT * FROM notifications WHERE postedAt BETWEEN :start AND :end ORDER BY postedAt DESC")
    suspend fun getByDateRange(start: Long, end: Long): List<NotificationEntity>

    @Query("SELECT * FROM notifications ORDER BY postedAt DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE category = 'UNCATEGORIZED' ORDER BY postedAt ASC LIMIT :limit")
    suspend fun getUnclassified(limit: Int): List<NotificationEntity>

    @Query("UPDATE notifications SET category = :category, importance = :importance, summary = :summary WHERE id = :id")
    suspend fun updateClassification(id: Long, category: String, importance: Int, summary: String)

    @Query("SELECT * FROM notifications WHERE postedAt BETWEEN :start AND :end AND (title LIKE '%' || :keyword || '%' OR text LIKE '%' || :keyword || '%') ORDER BY postedAt DESC LIMIT 50")
    suspend fun searchByKeyword(keyword: String, start: Long, end: Long): List<NotificationEntity>

    @Query("SELECT category, COUNT(*) as count FROM notifications GROUP BY category")
    fun observeCountsByCategory(): Flow<List<CategoryCount>>
}
~~~

BriefDao 与 ChatMessageDao 使用骨架精确 SQL。AppDatabase：

~~~kotlin
@Database(
    entities = [NotificationEntity::class, BriefEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun briefDao(): BriefDao
    abstract fun chatMessageDao(): ChatMessageDao
}
~~~

AppModule：

~~~kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "calm_inbox.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideNotificationDao(db: AppDatabase): NotificationDao = db.notificationDao()
    @Provides fun provideBriefDao(db: AppDatabase): BriefDao = db.briefDao()
    @Provides fun provideChatDao(db: AppDatabase): ChatMessageDao = db.chatMessageDao()
}
~~~

Room schema 导出到 app/schemas/1.json 并提交。若 kapt 提示 schema location 未设置，用 Room 2.6.1 官方 kapt 参数配置，目标文件不变。

## Step 4: 确认通过并回归

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.database.*"
.\gradlew.bat :app:testDebugUnitTest
~~~

**Expected:** 三个 DAO 测试类全部 PASSED；全量单测无回归。

## Step 5: Commit

~~~powershell
git add app/src/main/java/com/calm/inbox/core/database app/src/main/java/com/calm/inbox/di/AppModule.kt app/src/test/java/com/calm/inbox/core/database app/schemas
git commit -m "feat(database): add notification, brief, and chat persistence contracts"
~~~
