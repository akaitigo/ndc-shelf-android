package dev.ndcshelf.app.data.repository

import androidx.room.withTransaction
import dev.ndcshelf.app.R
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.SavedSearchEntity
import dev.ndcshelf.app.data.local.TagAssignmentEntity
import dev.ndcshelf.app.data.local.TagEntity
import dev.ndcshelf.app.data.local.TagUsageRow
import dev.ndcshelf.app.data.sync.syncDelete
import dev.ndcshelf.app.data.sync.toSyncUpsert
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibrarySort
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.SavedSearch
import dev.ndcshelf.app.domain.model.Tag
import dev.ndcshelf.app.domain.model.TagAssignment
import dev.ndcshelf.app.domain.model.TagColorRole
import dev.ndcshelf.app.domain.model.TagNameRules
import dev.ndcshelf.app.domain.model.TagNameValidation
import dev.ndcshelf.app.domain.model.TagWithUsage
import dev.ndcshelf.app.domain.repository.SavedSearchMutationResult
import dev.ndcshelf.app.domain.repository.TagAssignmentResult
import dev.ndcshelf.app.domain.repository.TagMutationResult
import dev.ndcshelf.app.domain.repository.TagRepository
import dev.ndcshelf.app.domain.sync.SyncMutation
import dev.ndcshelf.app.domain.sync.SyncMutationJournal
import dev.ndcshelf.app.domain.text.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class RoomTagRepository(
    private val database: AppDatabase,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val syncJournal: SyncMutationJournal = SyncMutationJournal.Disabled,
) : TagRepository {
    private val dao = database.tagDao()

    override fun observeTags(): Flow<List<TagWithUsage>> = dao.observeTagUsage().map { rows -> rows.map(TagUsageRow::toDomain) }

    override fun observeAssignments(): Flow<List<TagAssignment>> =
        dao.observeAssignments().map { rows ->
            rows.map { row -> TagAssignment(row.id, row.tagId, row.workId, row.createdAt) }
        }

    override fun observeSavedSearches(): Flow<List<SavedSearch>> =
        dao.observeSavedSearches().map { rows -> rows.mapNotNull(SavedSearchEntity::toDomain) }

    override suspend fun getTagsSnapshot(): List<Tag> = dao.getAllTags().map(TagEntity::toDomain)

    override suspend fun getAssignmentsSnapshot(): List<TagAssignment> =
        dao.getAllAssignments().map { entity ->
            TagAssignment(entity.id, entity.tagId, entity.workId, entity.createdAt)
        }

    override suspend fun createTag(
        name: String,
        colorRole: TagColorRole,
    ): TagMutationResult {
        val normalized =
            when (val validation = TagNameRules.validate(name)) {
                is TagNameValidation.Invalid -> return TagMutationResult.Invalid(validation.reason)
                is TagNameValidation.Valid -> validation.normalized
            }
        return try {
            database.withTransaction {
                if (dao.countTags() >= TagNameRules.MAX_TAGS) {
                    return@withTransaction TagMutationResult.LimitReached
                }
                if (dao.findTagByName(normalized) != null) {
                    return@withTransaction TagMutationResult.Duplicate
                }
                val now = nowMillis()
                val entity =
                    TagEntity(
                        id = idFactory(),
                        name = normalized,
                        colorRole = colorRole.name,
                        createdAt = now,
                        updatedAt = now,
                    )
                dao.insertTag(entity)
                syncJournal.record(listOf(entity.toSyncUpsert()))
                TagMutationResult.Done(entity.toDomain())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            TagMutationResult.Failure
        }
    }

    override suspend fun updateTag(
        tagId: String,
        name: String,
        colorRole: TagColorRole,
    ): TagMutationResult {
        val normalized =
            when (val validation = TagNameRules.validate(name)) {
                is TagNameValidation.Invalid -> return TagMutationResult.Invalid(validation.reason)
                is TagNameValidation.Valid -> validation.normalized
            }
        return try {
            database.withTransaction {
                val previous =
                    dao.findTagById(tagId)
                        ?: return@withTransaction TagMutationResult.NotFound
                val sameName = dao.findTagByName(normalized)
                if (sameName != null && sameName.id != tagId) {
                    return@withTransaction TagMutationResult.Duplicate
                }
                if (previous.name == normalized && previous.colorRole == colorRole.name) {
                    return@withTransaction TagMutationResult.Done(previous.toDomain())
                }
                val current =
                    previous.copy(
                        name = normalized,
                        colorRole = colorRole.name,
                        updatedAt = maxOf(nowMillis(), previous.updatedAt + 1),
                    )
                dao.upsertTag(current)
                syncJournal.record(listOf(current.toSyncUpsert()))
                TagMutationResult.Done(current.toDomain())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            TagMutationResult.Failure
        }
    }

    override suspend fun mergeTags(
        sourceTagId: String,
        targetTagId: String,
    ): TagMutationResult =
        try {
            database.withTransaction {
                if (sourceTagId == targetTagId) {
                    return@withTransaction TagMutationResult.Invalid(UiMessage(R.string.validation_tag_merge_same))
                }
                val source =
                    dao.findTagById(sourceTagId)
                        ?: return@withTransaction TagMutationResult.NotFound
                val target =
                    dao.findTagById(targetTagId)
                        ?: return@withTransaction TagMutationResult.NotFound
                val mutations = mutableListOf<SyncMutation>()
                val now = nowMillis()
                dao.findAssignmentsForTag(source.id).forEach { assignment ->
                    check(dao.deleteAssignmentById(assignment.id) == 1)
                    mutations += syncDelete("tagAssignment", assignment.id)
                    if (dao.findAssignment(target.id, assignment.workId) == null) {
                        val moved =
                            TagAssignmentEntity(
                                id = idFactory(),
                                tagId = target.id,
                                workId = assignment.workId,
                                createdAt = now,
                            )
                        dao.insertAssignment(moved)
                        mutations += moved.toSyncUpsert()
                    }
                }
                check(dao.deleteTagById(source.id) == 1)
                mutations += syncDelete("tag", source.id)
                syncJournal.record(mutations)
                TagMutationResult.Done(target.toDomain())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            TagMutationResult.Failure
        }

    override suspend fun deleteTag(tagId: String): TagMutationResult =
        try {
            database.withTransaction {
                val tag = dao.findTagById(tagId) ?: return@withTransaction TagMutationResult.NotFound
                val mutations = mutableListOf<SyncMutation>()
                dao.findAssignmentsForTag(tagId).forEach { assignment ->
                    mutations += syncDelete("tagAssignment", assignment.id)
                }
                check(dao.deleteTagById(tagId) == 1)
                mutations += syncDelete("tag", tagId)
                syncJournal.record(mutations)
                TagMutationResult.Done(tag.toDomain())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            TagMutationResult.Failure
        }

    override suspend fun setTagOnWorks(
        tagId: String,
        workIds: Set<String>,
        assigned: Boolean,
    ): TagAssignmentResult =
        try {
            database.withTransaction {
                dao.findTagById(tagId) ?: return@withTransaction TagAssignmentResult.NotFound
                val mutations = mutableListOf<SyncMutation>()
                var changed = 0
                val now = nowMillis()
                workIds.sorted().forEach { workId ->
                    val existing = dao.findAssignment(tagId, workId)
                    if (assigned && existing == null) {
                        if (database.libraryDao().findWorkById(workId) == null) {
                            return@withTransaction TagAssignmentResult.NotFound
                        }
                        val assignment =
                            TagAssignmentEntity(
                                id = idFactory(),
                                tagId = tagId,
                                workId = workId,
                                createdAt = now,
                            )
                        dao.insertAssignment(assignment)
                        mutations += assignment.toSyncUpsert()
                        changed += 1
                    } else if (!assigned && existing != null) {
                        check(dao.deleteAssignmentById(existing.id) == 1)
                        mutations += syncDelete("tagAssignment", existing.id)
                        changed += 1
                    }
                }
                syncJournal.record(mutations)
                TagAssignmentResult.Applied(changed)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            TagAssignmentResult.Failure
        }

    override suspend fun saveSearch(
        name: String,
        criteria: LibrarySearchCriteria,
    ): SavedSearchMutationResult {
        val normalized =
            when (val validation = TagNameRules.validate(name)) {
                is TagNameValidation.Invalid -> return SavedSearchMutationResult.Invalid(validation.reason)
                is TagNameValidation.Valid -> validation.normalized
            }
        return try {
            database.withTransaction {
                if (dao.countSavedSearches() >= TagNameRules.MAX_SAVED_SEARCHES) {
                    return@withTransaction SavedSearchMutationResult.LimitReached
                }
                if (dao.findSavedSearchByName(normalized) != null) {
                    return@withTransaction SavedSearchMutationResult.Duplicate
                }
                val now = nowMillis()
                val entity =
                    SavedSearchEntity(
                        id = idFactory(),
                        name = normalized,
                        criteriaJson = criteria.toCriteriaJson(),
                        createdAt = now,
                        updatedAt = now,
                    )
                dao.insertSavedSearch(entity)
                syncJournal.record(listOf(entity.toSyncUpsert()))
                SavedSearchMutationResult.Done(entity.toDomain())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            SavedSearchMutationResult.Failure
        }
    }

    override suspend fun renameSavedSearch(
        searchId: String,
        name: String,
    ): SavedSearchMutationResult {
        val normalized =
            when (val validation = TagNameRules.validate(name)) {
                is TagNameValidation.Invalid -> return SavedSearchMutationResult.Invalid(validation.reason)
                is TagNameValidation.Valid -> validation.normalized
            }
        return try {
            database.withTransaction {
                val previous =
                    dao.findSavedSearchById(searchId)
                        ?: return@withTransaction SavedSearchMutationResult.NotFound
                val sameName = dao.findSavedSearchByName(normalized)
                if (sameName != null && sameName.id != searchId) {
                    return@withTransaction SavedSearchMutationResult.Duplicate
                }
                val current =
                    previous.copy(
                        name = normalized,
                        updatedAt = maxOf(nowMillis(), previous.updatedAt + 1),
                    )
                dao.upsertSavedSearch(current)
                syncJournal.record(listOf(current.toSyncUpsert()))
                SavedSearchMutationResult.Done(current.toDomain())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            SavedSearchMutationResult.Failure
        }
    }

    override suspend fun deleteSavedSearch(searchId: String): SavedSearchMutationResult =
        try {
            database.withTransaction {
                dao.findSavedSearchById(searchId)
                    ?: return@withTransaction SavedSearchMutationResult.NotFound
                check(dao.deleteSavedSearchById(searchId) == 1)
                syncJournal.record(listOf(syncDelete("savedSearch", searchId)))
                SavedSearchMutationResult.Done(null)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            SavedSearchMutationResult.Failure
        }
}

private fun TagEntity.toDomain(): Tag =
    Tag(
        id = id,
        name = name,
        colorRole = colorRole.toColorRole(),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun TagUsageRow.toDomain(): TagWithUsage =
    TagWithUsage(
        tag =
            Tag(
                id = id,
                name = name,
                colorRole = colorRole.toColorRole(),
                createdAt = createdAt,
                updatedAt = updatedAt,
            ),
        taggedWorkCount = taggedWorkCount,
    )

private fun String.toColorRole(): TagColorRole = TagColorRole.entries.firstOrNull { it.name == this } ?: TagColorRole.GRAY

/**
 * 保存済み検索の条件JSON。query / readingStatus / sort / tagIds だけを保存し、
 * 詳細表示中のEdition IDのような一時状態は保存しない。
 */
internal fun LibrarySearchCriteria.toCriteriaJson(): String =
    buildJsonObject {
        put("query", JsonPrimitive(normalizedQuery))
        put("readingStatus", readingStatus?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("sort", JsonPrimitive(sort.name))
        put(
            "tagIds",
            buildJsonArray { normalizedTagIds.sorted().forEach { add(JsonPrimitive(it)) } },
        )
    }.toString()

internal fun parseCriteriaJson(raw: String): LibrarySearchCriteria? =
    try {
        val root = Json.parseToJsonElement(raw).jsonObject
        LibrarySearchCriteria(
            query = root["query"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            readingStatus =
                root["readingStatus"]?.jsonPrimitive?.contentOrNull?.let { name ->
                    ReadingStatus.entries.firstOrNull { it.name == name }
                },
            sort =
                root["sort"]?.jsonPrimitive?.contentOrNull?.let { name ->
                    LibrarySort.entries.firstOrNull { it.name == name }
                } ?: LibrarySort.ADDED_NEWEST,
            tagIds =
                root["tagIds"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.take(TagNameRules.MAX_TAG_FILTERS)
                    ?.toSet()
                    .orEmpty(),
        )
    } catch (_: Exception) {
        null
    }

internal fun SavedSearchEntity.toDomain(): SavedSearch? =
    parseCriteriaJson(criteriaJson)?.let { criteria ->
        SavedSearch(
            id = id,
            name = name,
            criteria = criteria,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
