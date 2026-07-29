package dev.ndcshelf.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

const val APP_DATABASE_VERSION = 12

@Database(
    entities = [
        BookWorkEntity::class,
        WorkGroupEntity::class,
        WorkGroupMembershipEntity::class,
        SeriesEntity::class,
        SeriesMembershipEntity::class,
        SeriesWatchEntity::class,
        SeriesReleaseCandidateEntity::class,
        BookEditionEntity::class,
        LocationRoomEntity::class,
        LocationShelfEntity::class,
        LocationTierEntity::class,
        OwnedCopyEntity::class,
        WishlistItemEntity::class,
        ScanSessionEntity::class,
        ScanAttemptEntity::class,
        SyncSettingsEntity::class,
        SyncOperationEntity::class,
        SyncFieldStateEntity::class,
        SyncTombstoneEntity::class,
        SyncCursorEntity::class,
        SyncAcknowledgementEntity::class,
        SyncConflictEntity::class,
        SyncUnresolvedDependencyEntity::class,
    ],
    version = APP_DATABASE_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val DATABASE_NAME = "ndc-shelf.db"

        // Register every manual migration here so production and migration tests use one graph.
        val MIGRATIONS: List<Migration> = listOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
        )
    }

    abstract fun libraryDao(): LibraryDao

    abstract fun locationDao(): LocationDao

    abstract fun seriesDao(): SeriesDao

    abstract fun workGroupDao(): WorkGroupDao

    abstract fun seriesWatchDao(): SeriesWatchDao

    abstract fun syncDao(): SyncDao
}

private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_settings (
                id INTEGER NOT NULL PRIMARY KEY,
                enabled INTEGER NOT NULL,
                deviceId TEXT,
                nextCounter INTEGER NOT NULL,
                lastSuccessfulAt INTEGER,
                requiresReregistration INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT OR IGNORE INTO sync_settings " +
                "(id, enabled, deviceId, nextCounter, lastSuccessfulAt, requiresReregistration) " +
                "VALUES (1, 0, NULL, 0, NULL, 0)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_operations (
                operationId TEXT NOT NULL PRIMARY KEY,
                deviceId TEXT NOT NULL,
                counter INTEGER NOT NULL,
                transactionId TEXT NOT NULL,
                transactionIndex INTEGER NOT NULL,
                transactionSize INTEGER NOT NULL,
                entityType TEXT NOT NULL,
                entityId TEXT NOT NULL,
                kind TEXT NOT NULL,
                fieldValuesJson TEXT NOT NULL,
                causalContextJson TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                state TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_operations_deviceId_counter " +
                "ON sync_operations(deviceId, counter)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_sync_operations_transactionId " +
                "ON sync_operations(transactionId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_sync_operations_state_deviceId_counter " +
                "ON sync_operations(state, deviceId, counter)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_field_states (
                entityType TEXT NOT NULL,
                entityId TEXT NOT NULL,
                fieldName TEXT NOT NULL,
                valueJson TEXT NOT NULL,
                winnerDeviceId TEXT NOT NULL,
                winnerCounter INTEGER NOT NULL,
                causalContextJson TEXT NOT NULL,
                PRIMARY KEY(entityType, entityId, fieldName)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_sync_field_states_winnerDeviceId_winnerCounter " +
                "ON sync_field_states(winnerDeviceId, winnerCounter)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_tombstones (
                entityType TEXT NOT NULL,
                entityId TEXT NOT NULL,
                deletingDeviceId TEXT NOT NULL,
                deletingCounter INTEGER NOT NULL,
                deletedAt INTEGER NOT NULL,
                retainUntil INTEGER NOT NULL,
                PRIMARY KEY(entityType, entityId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_sync_tombstones_retainUntil " +
                "ON sync_tombstones(retainUntil)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_cursors (
                deviceId TEXT NOT NULL PRIMARY KEY,
                receivedCounter INTEGER NOT NULL,
                processedCounter INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_acknowledgements (
                acknowledgingDeviceId TEXT NOT NULL,
                observedDeviceId TEXT NOT NULL,
                counter INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(acknowledgingDeviceId, observedDeviceId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_sync_acknowledgements_observedDeviceId_counter " +
                "ON sync_acknowledgements(observedDeviceId, counter)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_conflicts (
                id TEXT NOT NULL PRIMARY KEY,
                transactionId TEXT NOT NULL,
                entityType TEXT NOT NULL,
                entityId TEXT NOT NULL,
                fieldName TEXT NOT NULL,
                winnerValueJson TEXT NOT NULL,
                loserValueJson TEXT NOT NULL,
                winnerDeviceId TEXT NOT NULL,
                winnerCounter INTEGER NOT NULL,
                loserDeviceId TEXT NOT NULL,
                loserCounter INTEGER NOT NULL,
                detectedAt INTEGER NOT NULL,
                resolvedOperationId TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_sync_conflicts_resolvedOperationId_detectedAt " +
                "ON sync_conflicts(resolvedOperationId, detectedAt)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_sync_conflicts_entityType_entityId " +
                "ON sync_conflicts(entityType, entityId)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_unresolved_dependencies (
                operationId TEXT NOT NULL,
                entityType TEXT NOT NULL,
                entityId TEXT NOT NULL,
                PRIMARY KEY(operationId, entityType, entityId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_sync_unresolved_dependencies_entityType_entityId " +
                "ON sync_unresolved_dependencies(entityType, entityId)",
        )
    }
}

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS series_watches (
                seriesId TEXT NOT NULL PRIMARY KEY,
                queryTitle TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                lastCheckedAt INTEGER,
                lastSuccessfulAt INTEGER,
                FOREIGN KEY(seriesId) REFERENCES series(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_series_watches_enabled ON series_watches(enabled)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS series_release_candidates (
                id TEXT NOT NULL PRIMARY KEY,
                seriesId TEXT NOT NULL,
                sourceRecordId TEXT NOT NULL,
                title TEXT NOT NULL,
                primaryAuthor TEXT NOT NULL,
                isbn13 TEXT,
                publisher TEXT,
                publishedDate TEXT,
                firstSeenAt INTEGER NOT NULL,
                lastSeenAt INTEGER NOT NULL,
                notifiedAt INTEGER,
                FOREIGN KEY(seriesId) REFERENCES series(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_series_release_candidates_seriesId " +
                "ON series_release_candidates(seriesId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "index_series_release_candidates_seriesId_sourceRecordId " +
                "ON series_release_candidates(seriesId, sourceRecordId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_series_release_candidates_isbn13 " +
                "ON series_release_candidates(isbn13)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_series_release_candidates_notifiedAt " +
                "ON series_release_candidates(notifiedAt)",
        )
    }
}

private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS work_groups (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                primaryAuthor TEXT NOT NULL,
                seriesSubstitutionEnabled INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_work_groups_title_id ON work_groups(title, id)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS work_group_memberships (
                id TEXT NOT NULL PRIMARY KEY,
                groupId TEXT NOT NULL,
                workId TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(groupId) REFERENCES work_groups(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(workId) REFERENCES book_works(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_work_group_memberships_groupId " +
                "ON work_group_memberships(groupId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_work_group_memberships_workId " +
                "ON work_group_memberships(workId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_work_group_memberships_groupId_workId " +
                "ON work_group_memberships(groupId, workId)",
        )
    }
}

private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE series_memberships " +
                "ADD COLUMN origin TEXT NOT NULL DEFAULT 'MANUAL'",
        )
        db.execSQL(
            "ALTER TABLE series_memberships " +
                "ADD COLUMN confirmedBy TEXT NOT NULL DEFAULT 'USER'",
        )
        db.execSQL(
            "ALTER TABLE series_memberships " +
                "ADD COLUMN sourceTitle TEXT NOT NULL DEFAULT ''",
        )
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS series (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_series_name_id ON series(name, id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS series_memberships (
                id TEXT NOT NULL PRIMARY KEY,
                seriesId TEXT NOT NULL,
                workId TEXT NOT NULL,
                sortOrderKey TEXT NOT NULL,
                volumeLabel TEXT NOT NULL,
                type TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(seriesId) REFERENCES series(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(workId) REFERENCES book_works(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_series_memberships_seriesId " +
                "ON series_memberships(seriesId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_series_memberships_workId " +
                "ON series_memberships(workId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_series_memberships_seriesId_workId " +
                "ON series_memberships(seriesId, workId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_series_memberships_seriesId_sortOrderKey " +
                "ON series_memberships(seriesId, sortOrderKey)",
        )
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE book_editions RENAME TO book_editions_old")
        db.execSQL(
            """
            CREATE TABLE book_editions (
                id TEXT NOT NULL PRIMARY KEY,
                workId TEXT NOT NULL,
                isbn13 TEXT,
                publisher TEXT,
                publishedYear INTEGER,
                coverUrl TEXT,
                ndcCode TEXT,
                ndcEdition TEXT,
                classificationSource TEXT NOT NULL,
                bibliographicSource TEXT NOT NULL,
                FOREIGN KEY(workId) REFERENCES book_works(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO book_editions (
                id, workId, isbn13, publisher, publishedYear, coverUrl,
                ndcCode, ndcEdition, classificationSource, bibliographicSource
            )
            SELECT id, workId, isbn13, publisher, publishedYear, coverUrl,
                ndcCode, ndcEdition, classificationSource, 'NDL'
            FROM book_editions_old
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE owned_copies_new (
                id TEXT NOT NULL PRIMARY KEY,
                editionId TEXT NOT NULL,
                mediaType TEXT NOT NULL,
                location TEXT NOT NULL,
                readingStatus TEXT NOT NULL,
                addedAt INTEGER NOT NULL,
                tierId TEXT,
                shelfOrderKey TEXT,
                copyLabel TEXT NOT NULL,
                FOREIGN KEY(editionId) REFERENCES book_editions(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(tierId) REFERENCES location_tiers(id)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO owned_copies_new (
                id, editionId, mediaType, location, readingStatus, addedAt,
                tierId, shelfOrderKey, copyLabel
            )
            SELECT id, editionId, mediaType, location, readingStatus, addedAt,
                tierId, shelfOrderKey, copyLabel
            FROM owned_copies
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE wishlist_items_new (
                editionId TEXT NOT NULL PRIMARY KEY,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(editionId) REFERENCES book_editions(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO wishlist_items_new (editionId, status, createdAt, updatedAt)
            SELECT editionId, status, createdAt, updatedAt FROM wishlist_items
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE owned_copies")
        db.execSQL("DROP TABLE wishlist_items")
        db.execSQL("DROP TABLE book_editions_old")
        db.execSQL("ALTER TABLE owned_copies_new RENAME TO owned_copies")
        db.execSQL("ALTER TABLE wishlist_items_new RENAME TO wishlist_items")
        db.execSQL("CREATE INDEX index_book_editions_workId ON book_editions(workId)")
        db.execSQL("CREATE UNIQUE INDEX index_book_editions_isbn13 ON book_editions(isbn13)")
        db.execSQL("CREATE INDEX index_owned_copies_editionId ON owned_copies(editionId)")
        db.execSQL("CREATE INDEX index_owned_copies_tierId ON owned_copies(tierId)")
        db.execSQL(
            "CREATE INDEX index_owned_copies_tierId_shelfOrderKey " +
                "ON owned_copies(tierId, shelfOrderKey)",
        )
        db.execSQL("CREATE INDEX index_wishlist_items_status ON wishlist_items(status)")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scan_sessions (
                id TEXT NOT NULL PRIMARY KEY,
                startedAt INTEGER NOT NULL,
                endedAt INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_scan_sessions_endedAt ON scan_sessions(endedAt)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scan_attempts (
                id TEXT NOT NULL PRIMARY KEY,
                sessionId TEXT NOT NULL,
                isbn TEXT NOT NULL,
                outcome TEXT NOT NULL,
                copyId TEXT,
                copySnapshot TEXT,
                attemptedAt INTEGER NOT NULL,
                undoneAt INTEGER,
                FOREIGN KEY(sessionId) REFERENCES scan_sessions(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_scan_attempts_sessionId ON scan_attempts(sessionId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_scan_attempts_copyId ON scan_attempts(copyId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_scan_attempts_attemptedAt " +
                "ON scan_attempts(attemptedAt)",
        )
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS wishlist_items (
                editionId TEXT NOT NULL PRIMARY KEY,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(editionId) REFERENCES book_editions(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_wishlist_items_status ON wishlist_items(status)",
        )
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE owned_copies ADD COLUMN copyLabel TEXT NOT NULL DEFAULT '所蔵本'",
        )
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE owned_copies ADD COLUMN shelfOrderKey TEXT")
        db.execSQL(
            """
            UPDATE owned_copies
            SET shelfOrderKey = printf('%016x', addedAt) || lower(hex(id))
            WHERE tierId IS NOT NULL
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_owned_copies_tierId_shelfOrderKey " +
                "ON owned_copies(tierId, shelfOrderKey)",
        )
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS location_rooms (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_location_rooms_name " +
                "ON location_rooms(name)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS location_shelves (
                id TEXT NOT NULL PRIMARY KEY,
                roomId TEXT NOT NULL,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                FOREIGN KEY(roomId) REFERENCES location_rooms(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_location_shelves_roomId " +
                "ON location_shelves(roomId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_location_shelves_roomId_name " +
                "ON location_shelves(roomId, name)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS location_tiers (
                id TEXT NOT NULL PRIMARY KEY,
                shelfId TEXT NOT NULL,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                FOREIGN KEY(shelfId) REFERENCES location_shelves(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_location_tiers_shelfId " +
                "ON location_tiers(shelfId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_location_tiers_shelfId_name " +
                "ON location_tiers(shelfId, name)",
        )
        db.execSQL(
            """
            CREATE TABLE owned_copies_new (
                id TEXT NOT NULL PRIMARY KEY,
                editionId TEXT NOT NULL,
                mediaType TEXT NOT NULL,
                location TEXT NOT NULL,
                readingStatus TEXT NOT NULL,
                addedAt INTEGER NOT NULL,
                tierId TEXT,
                FOREIGN KEY(editionId) REFERENCES book_editions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(tierId) REFERENCES location_tiers(id) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO owned_copies_new (
                id, editionId, mediaType, location, readingStatus, addedAt, tierId
            )
            SELECT id, editionId, mediaType, location, readingStatus, addedAt, NULL
            FROM owned_copies
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE owned_copies")
        db.execSQL("ALTER TABLE owned_copies_new RENAME TO owned_copies")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_owned_copies_editionId ON owned_copies(editionId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_owned_copies_tierId ON owned_copies(tierId)",
        )
    }
}
