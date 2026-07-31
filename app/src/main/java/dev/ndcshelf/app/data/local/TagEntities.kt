package dev.ndcshelf.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 蔵書横断整理のタグ。作品（book_works）単位で付与する。
 * `id` は端末間で衝突しない独立UUIDで、同期時の識別子として使う。
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorRole: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "tag_assignments",
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BookWorkEntity::class,
            parentColumns = ["id"],
            childColumns = ["workId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["tagId"]),
        Index(value = ["workId"]),
        Index(value = ["tagId", "workId"], unique = true),
    ],
)
data class TagAssignmentEntity(
    @PrimaryKey val id: String,
    val tagId: String,
    val workId: String,
    val createdAt: Long,
)

/** 保存済み検索（検索条件コレクション）。criteriaJsonへ検索条件を保存する。 */
@Entity(
    tableName = "saved_searches",
    indices = [Index(value = ["name"], unique = true)],
)
data class SavedSearchEntity(
    @PrimaryKey val id: String,
    val name: String,
    val criteriaJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class TagUsageRow(
    val id: String,
    val name: String,
    val colorRole: String,
    val createdAt: Long,
    val updatedAt: Long,
    val taggedWorkCount: Int,
)

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY id")
    suspend fun getAllTags(): List<TagEntity>

    @Query("SELECT * FROM tag_assignments ORDER BY id")
    suspend fun getAllAssignments(): List<TagAssignmentEntity>

    @Query("SELECT * FROM saved_searches ORDER BY id")
    suspend fun getAllSavedSearches(): List<SavedSearchEntity>

    @Query(
        """
        SELECT
            tags.id AS id,
            tags.name AS name,
            tags.colorRole AS colorRole,
            tags.createdAt AS createdAt,
            tags.updatedAt AS updatedAt,
            COUNT(assignments.id) AS taggedWorkCount
        FROM tags
        LEFT JOIN tag_assignments AS assignments ON assignments.tagId = tags.id
        GROUP BY tags.id
        ORDER BY tags.name COLLATE NOCASE ASC, tags.id ASC
        """,
    )
    fun observeTagUsage(): Flow<List<TagUsageRow>>

    @Query("SELECT * FROM tag_assignments ORDER BY createdAt ASC, id ASC")
    fun observeAssignments(): Flow<List<TagAssignmentEntity>>

    @Query("SELECT * FROM saved_searches ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeSavedSearches(): Flow<List<SavedSearchEntity>>

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun countTags(): Int

    @Query("SELECT COUNT(*) FROM saved_searches")
    suspend fun countSavedSearches(): Int

    @Query("SELECT * FROM tags WHERE id = :tagId LIMIT 1")
    suspend fun findTagById(tagId: String): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findTagByName(name: String): TagEntity?

    @Query("SELECT * FROM tag_assignments WHERE tagId = :tagId ORDER BY createdAt ASC, id ASC")
    suspend fun findAssignmentsForTag(tagId: String): List<TagAssignmentEntity>

    @Query("SELECT * FROM tag_assignments WHERE workId = :workId ORDER BY createdAt ASC, id ASC")
    suspend fun findAssignmentsForWork(workId: String): List<TagAssignmentEntity>

    @Query(
        "SELECT * FROM tag_assignments WHERE tagId = :tagId AND workId = :workId LIMIT 1",
    )
    suspend fun findAssignment(
        tagId: String,
        workId: String,
    ): TagAssignmentEntity?

    @Query("SELECT * FROM saved_searches WHERE id = :searchId LIMIT 1")
    suspend fun findSavedSearchById(searchId: String): SavedSearchEntity?

    @Query("SELECT * FROM saved_searches WHERE name = :name LIMIT 1")
    suspend fun findSavedSearchByName(name: String): SavedSearchEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAssignment(assignment: TagAssignmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSavedSearch(savedSearch: SavedSearchEntity)

    @Upsert
    suspend fun upsertTag(tag: TagEntity)

    @Upsert
    suspend fun upsertTags(tags: List<TagEntity>)

    @Upsert
    suspend fun upsertAssignment(assignment: TagAssignmentEntity)

    @Upsert
    suspend fun upsertAssignments(assignments: List<TagAssignmentEntity>)

    @Upsert
    suspend fun upsertSavedSearch(savedSearch: SavedSearchEntity)

    @Upsert
    suspend fun upsertSavedSearches(savedSearches: List<SavedSearchEntity>)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTagById(tagId: String): Int

    @Query("DELETE FROM tag_assignments WHERE id = :assignmentId")
    suspend fun deleteAssignmentById(assignmentId: String): Int

    @Query("DELETE FROM saved_searches WHERE id = :searchId")
    suspend fun deleteSavedSearchById(searchId: String): Int

    @Query("DELETE FROM tags")
    suspend fun deleteAllTags()

    @Query("DELETE FROM tag_assignments")
    suspend fun deleteAllAssignments()

    @Query("DELETE FROM saved_searches")
    suspend fun deleteAllSavedSearches()
}
