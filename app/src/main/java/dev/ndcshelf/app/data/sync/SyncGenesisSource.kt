package dev.ndcshelf.app.data.sync

import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.domain.sync.SyncMutation

/**
 * 同期有効化時に既存domain dataをjournalへ取り込むgenesis mutation源。
 * 同期scope（SYNC_PROTOCOL.md 3.1節）のentityだけを対象にし、scan履歴・
 * series watch・release candidate等の除外dataを含めない。
 */
class SyncGenesisSource(
    private val database: AppDatabase,
) {
    /** 引数のentity IDだけを対象にする場合はincludeIdsを渡す（join時の差分用）。 */
    suspend fun collect(excludeEntityIds: Set<Pair<String, String>> = emptySet()): List<SyncMutation> {
        val mutations = mutableListOf<SyncMutation>()
        val libraryDao = database.libraryDao()
        val locationDao = database.locationDao()
        val seriesDao = database.seriesDao()
        val workGroupDao = database.workGroupDao()
        val readingSessionDao = database.readingSessionDao()

        fun include(
            entityType: String,
            entityId: String,
            mutation: SyncMutation,
        ) {
            if ((entityType to entityId) !in excludeEntityIds) mutations += mutation
        }

        libraryDao.getAllWorks().forEach { include("work", it.id, it.toSyncUpsert()) }
        locationDao.getRooms().forEach { include("locationRoom", it.id, it.toSyncUpsert()) }
        locationDao.getAllShelves().forEach { include("locationShelf", it.id, it.toSyncUpsert()) }
        locationDao.getAllTiers().forEach { include("locationTier", it.id, it.toSyncUpsert()) }
        libraryDao.getAllEditions().forEach { include("edition", it.id, it.toSyncUpsert()) }
        seriesDao.getAllSeries().forEach { include("series", it.id, it.toSyncUpsert()) }
        workGroupDao.getAllGroups().forEach { include("workGroup", it.id, it.toSyncUpsert()) }
        libraryDao.getAllCopies().forEach { include("ownedCopy", it.id, it.toSyncUpsert()) }
        libraryDao.getAllWishlistItems().forEach {
            include("wishlistItem", it.editionId, it.toSyncUpsert())
        }
        seriesDao.getAllMemberships().forEach {
            include("seriesMembership", it.id, it.toSyncUpsert())
        }
        workGroupDao.getAllMemberships().forEach {
            include("workGroupMembership", it.id, it.toSyncUpsert())
        }
        readingSessionDao.getAll().forEach { include("readingSession", it.id, it.toSyncUpsert()) }
        return mutations
    }

    /** join前のlocal既存entity IDを収集する（bootstrap後の差分genesis用）。 */
    suspend fun collectEntityIds(): Set<Pair<String, String>> = collect().map { it.entityType to it.entityId }.toSet()
}
