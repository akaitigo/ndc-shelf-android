package dev.ndcshelf.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.WorkGroupEntity
import dev.ndcshelf.app.data.local.WorkGroupMembershipEntity
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.EditionVariant
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.WorkGroup
import dev.ndcshelf.app.domain.model.WorkGroupMembership
import dev.ndcshelf.app.domain.model.WorkVariant
import dev.ndcshelf.app.domain.model.WorkVariantEditor
import dev.ndcshelf.app.domain.model.WorkVariantSuggestion
import dev.ndcshelf.app.domain.model.WorkVariantSuggestionConfidence
import dev.ndcshelf.app.domain.repository.WorkGroupMutationResult
import dev.ndcshelf.app.domain.repository.WorkGroupRepository
import dev.ndcshelf.app.domain.sync.SyncMutation
import dev.ndcshelf.app.domain.sync.SyncMutationJournal
import dev.ndcshelf.app.data.sync.syncDelete
import dev.ndcshelf.app.data.sync.toSyncUpsert
import kotlinx.coroutines.CancellationException
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

class RoomWorkGroupRepository(
    private val database: AppDatabase,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val syncJournal: SyncMutationJournal = SyncMutationJournal.Disabled,
) : WorkGroupRepository {
    private val groupDao = database.workGroupDao()

    override suspend fun editorFor(workId: String): WorkVariantEditor? = database.withTransaction {
        val snapshot = loadSnapshot()
        val source = snapshot.variants[workId] ?: return@withTransaction null
        val membership = snapshot.membershipsByWork[workId]
        val group = membership?.let { snapshot.groups[it.groupId] }
        val groupMembers = membership?.let { current ->
            snapshot.memberships.filter { it.groupId == current.groupId }
                .mapNotNull { snapshot.variants[it.workId] }
        }.orEmpty()
        val suggestions = snapshot.variants.values.asSequence()
            .filter { candidate -> candidate.workId != workId }
            .filter { candidate -> canSuggest(membership, candidate.membership) }
            .mapNotNull { candidate -> suggestion(source, candidate) }
            .sortedWith(
                compareBy<WorkVariantSuggestion> { it.confidence.ordinal }
                    .thenBy { it.work.title }
                    .thenBy { it.work.workId },
            )
            .take(MAX_SUGGESTIONS)
            .toList()
        WorkVariantEditor(source, group, groupMembers, suggestions)
    }

    override suspend fun link(
        sourceWorkId: String,
        targetWorkId: String,
        expectedSourceTitle: String,
        expectedTargetTitle: String,
        seriesSubstitutionEnabled: Boolean,
    ): WorkGroupMutationResult {
        if (sourceWorkId == targetWorkId || sourceWorkId.isBlank() || targetWorkId.isBlank()) {
            return WorkGroupMutationResult.Invalid
        }
        return mutate {
            val libraryDao = database.libraryDao()
            val source = libraryDao.findWorkById(sourceWorkId)
                ?: return@mutate WorkGroupMutationResult.Invalid
            val target = libraryDao.findWorkById(targetWorkId)
                ?: return@mutate WorkGroupMutationResult.Invalid
            if (source.title != expectedSourceTitle || target.title != expectedTargetTitle) {
                return@mutate WorkGroupMutationResult.Conflict
            }
            val sourceMembership = groupDao.findMembershipByWorkId(sourceWorkId)
            val targetMembership = groupDao.findMembershipByWorkId(targetWorkId)
            if (sourceMembership != null && targetMembership != null) {
                return@mutate WorkGroupMutationResult.Conflict
            }
            val now = nowMillis()
            val syncMutations = mutableListOf<SyncMutation>()
            val group = when {
                sourceMembership != null -> groupDao.findGroupById(sourceMembership.groupId)
                targetMembership != null -> groupDao.findGroupById(targetMembership.groupId)
                else -> null
            }
            val groupId = group?.id ?: idFactory().also { groupId ->
                val created = WorkGroupEntity(
                        id = groupId,
                        title = source.title,
                        primaryAuthor = source.primaryAuthor,
                        seriesSubstitutionEnabled = seriesSubstitutionEnabled,
                        createdAt = now,
                        updatedAt = now,
                    )
                groupDao.insertGroup(created)
                syncMutations += created.toSyncUpsert()
            }
            if (group != null) {
                groupDao.updateSeriesSubstitution(groupId, seriesSubstitutionEnabled, now)
                syncMutations += requireNotNull(groupDao.findGroupById(groupId)).toSyncUpsert()
            }
            if (sourceMembership == null) syncMutations += insertMembership(groupId, sourceWorkId, now).toSyncUpsert()
            if (targetMembership == null) syncMutations += insertMembership(groupId, targetWorkId, now).toSyncUpsert()
            syncJournal.record(syncMutations)
            WorkGroupMutationResult.Linked(groupId)
        }
    }

    override suspend fun unlink(membershipId: String): WorkGroupMutationResult = mutate {
        val membership = groupDao.findMembershipById(membershipId)
            ?: return@mutate WorkGroupMutationResult.Invalid
        if (groupDao.deleteMembership(membershipId) != 1) {
            return@mutate WorkGroupMutationResult.Conflict
        }
        val mutations = mutableListOf<SyncMutation>(syncDelete("workGroupMembership", membershipId))
        val remainingMemberships = groupDao.getMembershipsForGroup(membership.groupId)
        if (remainingMemberships.size < MIN_GROUP_SIZE) {
            groupDao.deleteGroup(membership.groupId)
            mutations += remainingMemberships.map { item ->
                syncDelete("workGroupMembership", item.id)
            }
            mutations += syncDelete("workGroup", membership.groupId)
        }
        syncJournal.record(mutations)
        WorkGroupMutationResult.Unlinked
    }

    override suspend fun setSeriesSubstitution(
        groupId: String,
        enabled: Boolean,
    ): WorkGroupMutationResult = mutate {
        if (groupDao.updateSeriesSubstitution(groupId, enabled, nowMillis()) == 1) {
            syncJournal.record(listOf(requireNotNull(groupDao.findGroupById(groupId)).toSyncUpsert()))
            WorkGroupMutationResult.Updated
        } else {
            WorkGroupMutationResult.Invalid
        }
    }

    private suspend fun mutate(block: suspend () -> WorkGroupMutationResult): WorkGroupMutationResult =
        try {
            database.withTransaction { block() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SQLiteConstraintException) {
            WorkGroupMutationResult.Conflict
        } catch (_: Exception) {
            WorkGroupMutationResult.Failure
        }

    private suspend fun insertMembership(groupId: String, workId: String, now: Long): WorkGroupMembershipEntity {
        val membership = WorkGroupMembershipEntity(
                id = idFactory(),
                groupId = groupId,
                workId = workId,
                createdAt = now,
            )
        groupDao.insertMembership(membership)
        return membership
    }

    private suspend fun loadSnapshot(): Snapshot {
        val libraryDao = database.libraryDao()
        val works = libraryDao.getAllWorks()
        val editions = libraryDao.getAllEditions()
        val copies = libraryDao.getAllCopies()
        val wishlist = libraryDao.getAllWishlistItems().associateBy { it.editionId }
        val groups = groupDao.getAllGroups().associate { it.id to it.toDomain() }
        val memberships = groupDao.getAllMemberships().map { it.toDomain() }
        val membershipsByWork = memberships.associateBy { it.workId }
        val copiesByEdition = copies.groupBy { it.editionId }
        val editionsByWork = editions.groupBy { it.workId }
        val variants = works.associate { work ->
            work.id to work.toVariant(
                editions = editionsByWork[work.id].orEmpty(),
                copiesByEdition = copiesByEdition,
                wishlistByEdition = wishlist,
                membership = membershipsByWork[work.id],
            )
        }
        return Snapshot(groups, memberships, membershipsByWork, variants)
    }

    private data class Snapshot(
        val groups: Map<String, WorkGroup>,
        val memberships: List<WorkGroupMembership>,
        val membershipsByWork: Map<String, WorkGroupMembership>,
        val variants: Map<String, WorkVariant>,
    )

    private companion object {
        const val MAX_SUGGESTIONS = 50
        const val MIN_GROUP_SIZE = 2
    }
}

private fun canSuggest(
    source: WorkGroupMembership?,
    target: WorkGroupMembership?,
): Boolean = source == null || target == null

private fun suggestion(source: WorkVariant, candidate: WorkVariant): WorkVariantSuggestion? {
    if (normalizeTitle(source.title) != normalizeTitle(candidate.title)) return null
    val sameAuthor = normalizeAuthor(source.primaryAuthor) == normalizeAuthor(candidate.primaryAuthor)
    return WorkVariantSuggestion(
        work = candidate,
        confidence = if (sameAuthor) {
            WorkVariantSuggestionConfidence.HIGH
        } else {
            WorkVariantSuggestionConfidence.MEDIUM
        },
        reason = if (sameAuthor) "タイトルと著者が一致" else "版表記を除いたタイトルが一致",
    )
}

private val EDITION_MARKER_PATTERN = Regex(
    "(?:[\\s　]*[\\(（\\[［【〈《]?(?:文庫|文庫版|新装版|電子版|完全版|改訂版|愛蔵版|新訂版|新版)[\\)）\\]］】〉》]?[\\s　]*)+$",
)
private val NORMALIZATION_PUNCTUATION = Regex("[\\s　・:：―ー\\-\\(（\\)）\\[［\\]］【】〈〉《》]")

internal fun normalizeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .replace(EDITION_MARKER_PATTERN, "")
    .replace(NORMALIZATION_PUNCTUATION, "")
    .lowercase(Locale.ROOT)

private fun normalizeAuthor(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .replace(NORMALIZATION_PUNCTUATION, "")
    .lowercase(Locale.ROOT)

private fun WorkGroupEntity.toDomain() = WorkGroup(
    id = id,
    title = title,
    primaryAuthor = primaryAuthor,
    seriesSubstitutionEnabled = seriesSubstitutionEnabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun WorkGroupMembershipEntity.toDomain() = WorkGroupMembership(id, groupId, workId, createdAt)

private fun BookWorkEntity.toVariant(
    editions: List<BookEditionEntity>,
    copiesByEdition: Map<String, List<dev.ndcshelf.app.data.local.OwnedCopyEntity>>,
    wishlistByEdition: Map<String, dev.ndcshelf.app.data.local.WishlistItemEntity>,
    membership: WorkGroupMembership?,
) = WorkVariant(
    workId = id,
    title = title,
    primaryAuthor = primaryAuthor,
    editions = editions.map { edition ->
        val copies = copiesByEdition[edition.id].orEmpty()
        EditionVariant(
            id = edition.id,
            isbn13 = edition.isbn13,
            publisher = edition.publisher,
            publishedYear = edition.publishedYear,
            coverUrl = edition.coverUrl,
            ndcCode = edition.ndcCode,
            ndcEdition = edition.ndcEdition,
            classificationSource = ClassificationSource.valueOf(edition.classificationSource),
            bibliographicSource = BibliographicSource.valueOf(edition.bibliographicSource),
            mediaTypes = copies.map { MediaType.valueOf(it.mediaType) }.toSet(),
            ownedCopyCount = copies.size,
            wishlistStatus = wishlistByEdition[edition.id]?.let { PurchaseStatus.valueOf(it.status) },
        )
    },
    membership = membership,
)
