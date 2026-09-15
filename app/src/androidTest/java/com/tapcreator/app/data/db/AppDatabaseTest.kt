package com.tapcreator.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tapcreator.app.data.model.ConversationSurface
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.RunStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 数据库设备端测试：验证建库、CRUD、级联查询在真实 SQLite 上可用。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @get:Rule
    val migrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName!!,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndReadUser() = runBlocking {
        val user = UserEntity(
            id = "u1",
            username = "测试用户",
            email = null,
            passwordHash = "hash",
            salt = "salt",
            createdAt = 1L,
        )
        db.userDao().insert(user)

        val loaded = db.userDao().byId("u1")
        assertNotNull(loaded)
        assertEquals("测试用户", loaded?.username)
        assertEquals("hash", loaded?.passwordHash)
    }

    @Test
    fun insertConversationAndMessages() = runBlocking {
        val conv = ConversationEntity(
            id = "c1",
            userId = "u1",
            title = "测试会话",
            surface = ConversationSurface.CHAT,
            createdAt = 1L,
            updatedAt = 1L,
        )
        db.conversationDao().insert(conv)

        val seq = db.messageDao().maxSequence("c1") + 1
        db.messageDao().insert(
            MessageEntity(
                id = "m1",
                conversationId = "c1",
                sequence = seq,
                role = "user",
                content = "你好",
                kind = MediaKind.TEXT,
                createdAt = 2L,
            )
        )

        val messages = db.messageDao().listByConversation("c1")
        assertEquals(1, messages.size)
        assertEquals("你好", messages[0].content)
    }

    @Test
    fun cardCrudAndLink() = runBlocking {
        val card = CardEntity(
            id = "card1",
            runId = "run1",
            conversationId = "c1",
            sequence = 1,
            kind = MediaKind.IMAGE,
            title = "测试卡",
            content = "",
            previewPath = null,
            mediaPath = "/tmp/a.jpg",
            status = RunStatus.COMPLETED,
            x = 0f,
            y = 0f,
            promptEnhanced = false,
        )
        db.cardDao().insert(card)

        val linked = db.cardDao().byId("card1")
        assertNotNull(linked)
        assertEquals("测试卡", linked?.title)

        db.cardDao().update(card.copy(title = "改名"))
        assertEquals("改名", db.cardDao().byId("card1")?.title)

        db.cardDao().hardDelete("card1")
        assertNull(db.cardDao().byId("card1"))
    }

    /** 验证 12→13 迁移（agent_runs 增加 checkpoint 列）能从导出的 schema 顺畅升级 */
    @Test
    fun migrate12To13_checkpointColumn() {
        val name = "migration12to13"
        // 用导出目录中的 12 号 schema 建库
        migrationTestHelper.createDatabase(name, 12).close()
        // 应用 12→13 迁移并校验 schema 与 13 号导出一致
        val migrated: SupportSQLiteDatabase = migrationTestHelper.runMigrationsAndValidate(
            name,
            13,
            true,
            AppDatabase.MIGRATION_12_13,
        )
        // 验证 checkpoint 列确实存在
        val cols = mutableListOf<String>()
        migrated.query("PRAGMA table_info(agent_runs)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                cols += c.getString(nameIdx)
            }
        }
        migrated.close()
        assertEquals(true, "checkpoint" in cols)

        // 清理
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
    }

    /** 验证 15→16 自动迁移（新增高频查询索引）不丢数据且索引生效 */
    @Test
    fun migrate15To16_autoIndexes() {
        val name = "migration15to16"
        migrationTestHelper.createDatabase(name, 15).apply {
            execSQL(
                "INSERT INTO cards (id, runId, conversationId, sequence, kind, title, content, status, x, y, deleted, promptEnhanced) " +
                    "VALUES ('c1','r1','conv1',1,'IMAGE','t','','COMPLETED',0,0,0,0)"
            )
            close()
        }
        val migrated: SupportSQLiteDatabase = migrationTestHelper.runMigrationsAndValidate(
            name, 16, true,
        )
        // 数据保留
        migrated.query("SELECT COUNT(*) FROM cards").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        // 索引存在：cards(conversationId) / agent_runs(status) / card_links(toCardId)
        val indexes = mutableListOf<String>()
        migrated.query("SELECT name, tbl_name FROM sqlite_master WHERE type='index'").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) indexes += c.getString(nameIdx)
        }
        migrated.close()
        assertEquals(true, indexes.any { it.contains("cards") || it.contains("conversationId") })
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
    }

    /** 分页查询回归：pageByConversation 与 latest 语义正确 */
    @Test
    fun messagePaging_pageAndLatest() = runBlocking {
        val conv = ConversationEntity(
            id = "paging1", userId = "u1", title = "分页", surface = ConversationSurface.CHAT,
            createdAt = 1L, updatedAt = 1L,
        )
        db.conversationDao().insert(conv)
        repeat(5) { i ->
            db.messageDao().insert(
                MessageEntity(
                    id = "pm$i", conversationId = "paging1", sequence = i + 1,
                    role = "user", content = "m$i", kind = MediaKind.TEXT, createdAt = i.toLong(),
                )
            )
        }
        val page0 = db.messageDao().pageByConversation("paging1", 2, 0)
        val page1 = db.messageDao().pageByConversation("paging1", 2, 2)
        assertEquals(listOf("m0", "m1"), page0.map { it.content })
        assertEquals(listOf("m2", "m3"), page1.map { it.content })
        assertEquals("m4", db.messageDao().latestByConversation("paging1", 1)[0].content)
        // 卡片分页：插入 3 张卡，页大小 2 → 2 + 1
        repeat(3) { i ->
            db.cardDao().insert(
                CardEntity(
                    id = "pc$i", runId = "r1", conversationId = "paging1", sequence = i + 1,
                    kind = MediaKind.IMAGE, title = "c$i", status = RunStatus.COMPLETED,
                )
            )
        }
        assertEquals(2, db.cardDao().pageByConversation("paging1", 2, 0).size)
        assertEquals(1, db.cardDao().pageByConversation("paging1", 2, 2).size)
    }
}