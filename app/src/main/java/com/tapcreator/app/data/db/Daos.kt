package com.tapcreator.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun byUsername(username: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<UserEntity?>

    @Insert
    suspend fun insert(user: UserEntity)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE token = :token")
    suspend fun deleteByToken(token: String)

    @Query("DELETE FROM sessions")
    suspend fun clear()

    @Query("SELECT * FROM sessions WHERE token = :token LIMIT 1")
    suspend fun byToken(token: String): SessionEntity?
}

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE userId = :userId ORDER BY updatedAt DESC")
    fun observeByUser(userId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ConversationEntity?

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sequence ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sequence ASC")
    suspend fun listByConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM messages WHERE conversationId = :conversationId")
    suspend fun maxSequence(conversationId: String): Int

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

@Dao
interface AgentRunDao {
    @Insert
    suspend fun insert(run: AgentRunEntity)

    @Update
    suspend fun update(run: AgentRunEntity)

    @Query("SELECT * FROM agent_runs WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_runs WHERE status IN ('PLANNING','RUNNING','PAUSED')")
    suspend fun staleActive(): List<AgentRunEntity>

    @Query("SELECT * FROM agent_runs WHERE conversationId = :conversationId AND status IN ('PLANNING','RUNNING','PAUSED') ORDER BY createdAt DESC LIMIT 1")
    suspend fun activeRun(conversationId: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs WHERE conversationId = :conversationId AND status IN ('PLANNING','RUNNING','PAUSED') ORDER BY createdAt DESC LIMIT 1")
    fun observeActive(conversationId: String): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_runs WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listByConversation(conversationId: String, limit: Int = 50): List<AgentRunEntity>

    @Query("SELECT * FROM agent_runs WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeByConversation(conversationId: String): Flow<List<AgentRunEntity>>
}

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE runId = :runId")
    suspend fun byRun(runId: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): TaskEntity?

    @Query("SELECT t.* FROM tasks t INNER JOIN agent_runs r ON t.runId = r.id WHERE r.conversationId = :conversationId ORDER BY t.createdAt DESC")
    fun observeByConversation(conversationId: String): Flow<List<TaskEntity>>
}

@Dao
interface AssetDao {
    @Insert
    suspend fun insert(asset: AssetEntity)

    @Update
    suspend fun update(asset: AssetEntity)

    @Query("SELECT * FROM assets WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): AssetEntity?

    /** 按磁盘路径反查素材行（删除卡片时用于连带清理对应磁盘文件与登记） */
    @Query("SELECT * FROM assets WHERE mediaPath = :path")
    suspend fun byMediaPath(path: String): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    fun observeByConversation(conversationId: String): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE folderId = :folderId ORDER BY createdAt DESC")
    suspend fun byFolder(folderId: String): List<AssetEntity>

    @Query("SELECT * FROM assets ORDER BY createdAt DESC")
    suspend fun all(): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE folderId IS NULL ORDER BY createdAt DESC")
    fun observeUnassigned(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE folderId = :folderId ORDER BY createdAt DESC")
    fun observeByFolder(folderId: String): Flow<List<AssetEntity>>

    @Query("UPDATE assets SET folderId = :folderId WHERE id = :assetId")
    suspend fun setFolder(assetId: String, folderId: String?)

    @Query("DELETE FROM assets WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface AssetFolderDao {
    @Insert
    suspend fun insert(folder: AssetFolderEntity)

    @Query("SELECT * FROM asset_folders ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AssetFolderEntity>>

    @Query("SELECT * FROM asset_folders ORDER BY createdAt DESC")
    suspend fun all(): List<AssetFolderEntity>

    @Query("SELECT * FROM asset_folders WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): AssetFolderEntity?

    @Query("SELECT * FROM asset_folders WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): AssetFolderEntity?

    @Query("DELETE FROM asset_folders WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(channel: ChannelEntity)

    @Query("SELECT * FROM channels ORDER BY priority ASC, name ASC")
    suspend fun all(): List<ChannelEntity>

    @Query("SELECT * FROM channels ORDER BY priority ASC, name ASC")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ChannelEntity?

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ModelOptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: ModelOptionEntity)

    @Query("SELECT * FROM model_options ORDER BY kind, name ASC")
    fun observeAllRaw(): Flow<List<ModelOptionEntity>>

    @Query("SELECT * FROM model_options WHERE enabled = 1 ORDER BY kind, name ASC")
    suspend fun all(): List<ModelOptionEntity>

    @Query("SELECT * FROM model_options WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ModelOptionEntity?

    @Query("SELECT * FROM model_options WHERE kind = :kind AND enabled = 1 ORDER BY isDefault DESC, name ASC")
    suspend fun byKind(kind: String): List<ModelOptionEntity>

    @Query("UPDATE model_options SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM model_options WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM model_options WHERE channelId = :channelId")
    suspend fun deleteByChannel(channelId: String)

    @Query("UPDATE model_options SET kind = :kind WHERE id = :id")
    suspend fun setKind(id: String, kind: String)
}

@Dao
interface CardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(card: CardEntity)

    /**
     * 待创作空白卡：以 runId='draft' 为标识，每会话最多一张，用于「返回后不丢失空白卡」。
     * 不在 timeline 中出现（runId 无对应 run），只在画布顶部单独呈现。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(card: CardEntity)

    @Query("SELECT * FROM cards WHERE conversationId = :conversationId AND runId = 'draft' LIMIT 1")
    suspend fun draftByConversation(conversationId: String): CardEntity?

    @Query("DELETE FROM cards WHERE conversationId = :conversationId AND runId = 'draft'")
    suspend fun deleteDraft(conversationId: String)

    @Update
    suspend fun update(card: CardEntity)

    @Query("SELECT * FROM cards WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): CardEntity?

    /** 按媒体路径反查仍在用的卡片（删除素材时用于安全守卫，防止悬空引用） */
    @Query("SELECT * FROM cards WHERE (mediaPath = :path OR previewPath = :path) AND deleted = 0 LIMIT 1")
    suspend fun byMediaPath(path: String): CardEntity?

    /** 按媒体路径反查同一会话内仍在用的卡片（用于把参考媒体归一化为工作区卡片时复用，避免重复建卡） */
    @Query("SELECT * FROM cards WHERE conversationId = :conversationId AND (mediaPath = :path OR previewPath = :path) AND deleted = 0 ORDER BY sequence ASC LIMIT 1")
    suspend fun byMediaPathInConversation(conversationId: String, path: String): CardEntity?

    @Query("SELECT * FROM cards WHERE conversationId = :conversationId AND deleted = 0 ORDER BY sequence ASC")
    fun observeByConversation(conversationId: String): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE conversationId = :conversationId AND deleted = 0 ORDER BY sequence ASC")
    suspend fun listByConversation(conversationId: String): List<CardEntity>

    @Query("SELECT * FROM cards WHERE runId = :runId ORDER BY sequence ASC")
    suspend fun byRun(runId: String): List<CardEntity>

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM cards WHERE conversationId = :conversationId")
    suspend fun maxSequence(conversationId: String): Int

    /** 更新卡片在画布上的坐标（平移缩放画布时持久化节点位置） */
    @Query("UPDATE cards SET x = :x, y = :y WHERE id = :id")
    suspend fun updatePosition(id: String, x: Float, y: Float)

    /** 仍被引用时删除：仅置 deleted=1 从面板隐藏，数据与磁盘文件保留 */
    @Query("UPDATE cards SET deleted = 1 WHERE id = :id")
    suspend fun softDelete(id: String)

    /** 无任何引用时删除：物理删除数据行（磁盘文件由调用方清理） */
    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("DELETE FROM cards WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

@Dao
interface CardLinkDao {
    @Insert
    suspend fun insert(link: CardLinkEntity)

    @Query("SELECT * FROM card_links WHERE toCardId = :toCardId")
    suspend fun incoming(toCardId: String): List<CardLinkEntity>

    @Query("SELECT * FROM card_links WHERE fromCardId = :fromCardId")
    suspend fun outgoing(fromCardId: String): List<CardLinkEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM card_links WHERE fromCardId = :fromCardId AND toCardId = :toCardId AND role = :role)")
    suspend fun exists(fromCardId: String, toCardId: String, role: String): Boolean

    /** 按 (源,目标,角色) 删除某一条关系边，用于在工作界面直接连/断线 */
    @Query("DELETE FROM card_links WHERE fromCardId = :fromCardId AND toCardId = :toCardId AND role = :role")
    suspend fun delete(fromCardId: String, toCardId: String, role: String)

    @Query("SELECT * FROM card_links")
    fun observeAll(): Flow<List<CardLinkEntity>>

    /** 删除某卡相关的所有关系边（作为源或被引用目标） */
    @Query("DELETE FROM card_links WHERE fromCardId = :cardId OR toCardId = :cardId")
    suspend fun deleteForCard(cardId: String)

    /** 删除某会话下所有卡片的关系边（清理孤儿引用） */
    @Query("DELETE FROM card_links WHERE fromCardId IN (SELECT id FROM cards WHERE conversationId = :conversationId) OR toCardId IN (SELECT id FROM cards WHERE conversationId = :conversationId)")
    suspend fun deleteByConversation(conversationId: String)
}

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(memory: MemoryEntity)

    /** 会话内最近记忆（Agent Loop 上下文注入用） */
    @Query("SELECT * FROM agent_memories WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentByConversation(conversationId: String, limit: Int = 50): List<MemoryEntity>

    /** 全文模糊检索记忆（recall 工具用），pattern 需自带 % 通配符 */
    @Query("SELECT * FROM agent_memories WHERE content LIKE :pattern ORDER BY createdAt DESC LIMIT :limit")
    suspend fun search(pattern: String, limit: Int = 10): List<MemoryEntity>
}

@Dao
interface TraceDao {
    @Insert
    suspend fun insert(trace: TraceEntity)

    @Query("SELECT * FROM agent_trace WHERE conversationId = :conversationId ORDER BY createdAt ASC, turn ASC LIMIT :limit")
    suspend fun byConversation(conversationId: String, limit: Int = 200): List<TraceEntity>

    /** 某一轮 Agent 执行的完整轨迹（read_trace 工具用；runId 空则最近一次） */
    @Query("SELECT * FROM agent_trace WHERE runId = :runId ORDER BY createdAt ASC, turn ASC LIMIT :limit")
    suspend fun byRun(runId: String, limit: Int = 200): List<TraceEntity>

    @Query("DELETE FROM agent_trace WHERE conversationId = :conversationId")
    suspend fun clearByConversation(conversationId: String)
}

/** 记录「一次 Agent 运行注入了哪些技能」，供用户反馈时精确归因赢率 */
@Dao
interface AgentRunSkillDao {
    @Insert
    suspend fun insertAll(entries: List<AgentRunSkillEntity>)

    /** 最近一次尚未被反馈消费的 Agent 运行（其注入技能对应的批次） */
    @Query("SELECT runId FROM agent_run_skills WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestPendingRunId(conversationId: String): String?

    @Query("SELECT skillId FROM agent_run_skills WHERE conversationId = :conversationId AND runId = :runId")
    suspend fun skillsForRun(conversationId: String, runId: String): List<String>

    @Query("DELETE FROM agent_run_skills WHERE conversationId = :conversationId AND runId = :runId")
    suspend fun deleteForRun(conversationId: String, runId: String)

    /**
     * 清理某会话的悬空注入批次：仅删 createdAt 早于 [beforeMs] 的记录，
     * 保留近期的、尚未被用户反馈消费的批次，避免同会话并发/误清。
     * 超过有效期（默认 30 分钟）的批次视为已废弃，可安全清理。
     */
    @Query("DELETE FROM agent_run_skills WHERE conversationId = :conversationId AND createdAt < :beforeMs")
    suspend fun clearForConversation(conversationId: String, beforeMs: Long)
}

@Dao
interface ConversationStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ConversationStateEntity)

    @Query("SELECT * FROM conversation_states WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ConversationStateEntity?

    @Query("DELETE FROM conversation_states WHERE id = :id")
    suspend fun delete(id: String)
}