package com.tapcreator.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UserEntity::class,
        SessionEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        AgentRunEntity::class,
        TaskEntity::class,
        AssetEntity::class,
        ChannelEntity::class,
        ModelOptionEntity::class,
        CardEntity::class,
        CardLinkEntity::class,
        MemoryEntity::class,
        AssetFolderEntity::class,
        TraceEntity::class,
        ConversationStateEntity::class,
    ],
    version = 15,
    // 导出 schema 快照：迁移出错时可 diff 出字段差异。schema 文件由 KSP 写入
    // app/schemas/（见 app/build.gradle.kts 的 ksp arg room.schemaLocation），入 git 留档。
    exportSchema = true,
)
@TypeConverters(TapcreatorConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun sessionDao(): SessionDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun agentRunDao(): AgentRunDao
    abstract fun taskDao(): TaskDao
    abstract fun assetDao(): AssetDao
    abstract fun channelDao(): ChannelDao
    abstract fun modelOptionDao(): ModelOptionDao
    abstract fun cardDao(): CardDao
    abstract fun cardLinkDao(): CardLinkDao
    abstract fun memoryDao(): MemoryDao
    abstract fun assetFolderDao(): AssetFolderDao

    abstract fun traceDao(): TraceDao

    abstract fun conversationStateDao(): ConversationStateDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN userId TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS agent_memories (" +
                        "id TEXT NOT NULL, " +
                        "conversationId TEXT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_memories_conversationId ON agent_memories (conversationId)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS asset_folders (" +
                        "id TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "note TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL("ALTER TABLE assets ADD COLUMN folderId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_folderId ON assets (folderId)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Mechanic：模型可选分辨率
                db.execSQL("ALTER TABLE model_options ADD COLUMN resolutions TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Agent run 可观测轨迹表
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS agent_trace (" +
                        "id TEXT NOT NULL, " +
                        "conversationId TEXT NOT NULL, " +
                        "turn INTEGER NOT NULL, " +
                        "role TEXT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_trace_conversationId ON agent_trace (conversationId)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 会话级 UI 状态（创作编辑现场 + Agent 现场 + 模型偏好）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversation_states (" +
                        "id TEXT NOT NULL, " +
                        "payloadJson TEXT NOT NULL DEFAULT '{}', " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(id))"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // agent_trace 增加 runId 维度，区分同会话的多次 Agent 执行
                db.execSQL("ALTER TABLE agent_trace ADD COLUMN runId TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_trace_runId ON agent_trace (runId)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Agent 自进化技能库
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS agent_skills (" +
                        "id TEXT NOT NULL, " +
                        "category TEXT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "risk TEXT NOT NULL, " +
                        "state TEXT NOT NULL, " +
                        "conversationId TEXT, " +
                        "sourceRunId TEXT, " +
                        "confidence REAL NOT NULL DEFAULT 0.5, " +
                        "usedCount INTEGER NOT NULL DEFAULT 0, " +
                        "successCount INTEGER NOT NULL DEFAULT 0, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_skills_state ON agent_skills (state)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Agent 运行 → 注入技能的关联：让用户反馈能精确归因赢率（而非模型自评）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS agent_run_skills (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "conversationId TEXT NOT NULL, " +
                        "runId TEXT NOT NULL, " +
                        "skillId TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_agent_run_skills_conversationId_runId ON agent_run_skills (conversationId, runId)"
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 成品卡记录提交时的提示词是否经 LLM 增强（promptOptimize 标识）
                db.execSQL("ALTER TABLE cards ADD COLUMN promptEnhanced INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 断点续传检查点：记录已完成的段文件路径，中断恢复时从断点续
                db.execSQL("ALTER TABLE agent_runs ADD COLUMN checkpoint TEXT")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 清理从未接入 agent 的「自进化技能库」遗留表（设计 Skill 走 DataStore，不依赖此表）
                db.execSQL("DROP TABLE IF EXISTS agent_skills")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 清理从未被调用的「Agent 运行注入技能」关联表（无任何读写方，纯死代码）
                db.execSQL("DROP TABLE IF EXISTS agent_run_skills")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tapcreator.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                // 去掉 destructive fallback：schema 不匹配直接启动崩溃（fail-fast），避免静默清空用户数据掩盖迁移遗漏。
                // 后续新增字段务必先补 Migration 再接 version。
                .build().also { instance = it }
            }
    }
}