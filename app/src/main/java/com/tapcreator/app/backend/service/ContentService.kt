package com.tapcreator.app.backend.service

import androidx.room.withTransaction
import com.tapcreator.app.data.db.AppDatabase
import com.tapcreator.app.data.db.AssetEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 删除结果：NOT_FOUND 卡不存在；HIDDEN_KEPT 被引用仅隐藏、数据保留；DELETED 无引用被物理删除 */
enum class DeleteResult { NOT_FOUND, HIDDEN_KEPT, DELETED }

/**
 * 内容删除与清理：卡片删除采用"引用感知"语义——
 * 被其他卡片引用（作为 reference 源）时只隐藏、保留数据与文件；
 * 无任何引用的卡片则连同其素材登记与磁盘文件一起物理删除，杜绝孤儿文件堆积。
 */
@Singleton
class ContentService @Inject constructor(
    private val db: AppDatabase,
) {

    suspend fun deleteCard(cardId: String): DeleteResult {
        val card = db.cardDao().byId(cardId) ?: return DeleteResult.NOT_FOUND

        // 被其他卡作为引用源（outgoing fromCardId==cardId）→ 仅从面板隐藏，数据/文件保留
        if (db.cardLinkDao().outgoing(cardId).isNotEmpty()) {
            db.cardDao().softDelete(cardId)
            return DeleteResult.HIDDEN_KEPT
        }

        // 无引用 → 先收集磁盘文件清单（含其背书素材的媒体文件），再事务删 DB 记录，
        // 事务提交成功后才物理删文件。顺序：DB 先提交 → 再删文件。
        // 若删文件中途进程崩溃，最坏是留孤儿文件（DB 已无记录），不会出现更糟的「DB 回滚但文件已删」。
        val files = mutableSetOf<String>()
        card.mediaPath?.let(files::add)
        card.previewPath?.let(files::add)

        val backingAssets = mutableListOf<AssetEntity>()
        listOfNotNull(card.mediaPath, card.previewPath).forEach { path ->
            backingAssets += db.assetDao().byMediaPath(path)
        }
        backingAssets.forEach { a ->
            a.mediaPath?.let(files::add)
            a.previewPath?.let(files::add)
        }

        db.withTransaction {
            backingAssets.forEach { db.assetDao().deleteById(it.id) }
            db.cardLinkDao().deleteForCard(cardId)
            db.cardDao().hardDelete(cardId)
        }
        // DB 事务已提交：物理删文件。失败只留孤儿文件，不影响数据一致性。
        files.forEach { runCatching { File(it).delete() } }
        return DeleteResult.DELETED
    }
}