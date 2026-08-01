package dev.ndcshelf.app.domain.repository

import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.BookEditValidationError
import dev.ndcshelf.app.domain.model.BookstoreBook
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibrarySort
import dev.ndcshelf.app.domain.model.LibraryStats
import dev.ndcshelf.app.domain.model.ManualBookDraft
import dev.ndcshelf.app.domain.model.ManualBookValidationError
import dev.ndcshelf.app.domain.model.ManualReconciliationPreview
import dev.ndcshelf.app.domain.model.PurchaseTransition
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.ScanSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface LibraryRepository {
    fun observeLibrary(): Flow<List<LibraryBook>>

    fun observeLibrary(criteria: LibrarySearchCriteria): Flow<List<LibraryBook>> =
        observeLibrary().map { books -> books.applyLibrarySearch(criteria) }

    fun observeLibraryStats(): Flow<LibraryStats> = observeLibrary().map { books ->
        LibraryStats(
            totalCount = books.size,
            classifiedCount = books.count { it.ndcCode != null },
            readingCount = books.count { it.readingStatus == ReadingStatus.READING },
        )
    }

    suspend fun getLibrarySnapshot(): List<LibraryBook> = observeLibrary().first()

    fun observeWishlist(): Flow<List<BookstoreBook>> = emptyFlow()

    fun observeScanSessions(): Flow<List<ScanSession>> = emptyFlow()

    suspend fun addFromIsbn(rawIsbn: String): AddBookResult

    suspend fun addManualBook(draft: ManualBookDraft): ManualBookResult =
        ManualBookResult.Failure

    suspend fun previewManualReconciliation(
        copyId: String,
        rawIsbn: String,
    ): ManualReconciliationLookupResult = ManualReconciliationLookupResult.Failure(AddBookFailure.SAVE)

    suspend fun confirmManualReconciliation(
        preview: ManualReconciliationPreview,
    ): ManualReconciliationApplyResult = ManualReconciliationApplyResult.Failure

    suspend fun startScanSession(): String? = null

    suspend fun activeScanSessionId(): String? = null

    suspend fun finishScanSession(sessionId: String): Boolean = false

    suspend fun recordScanAttempt(
        sessionId: String,
        rawIsbn: String,
        result: AddBookResult,
    ): Boolean = false

    suspend fun undoScanAttempt(attemptId: String): ScanUndoResult = ScanUndoResult.Failure

    suspend fun undoScanSession(sessionId: String): ScanUndoResult = ScanUndoResult.Failure

    suspend fun addAnotherCopy(rawIsbn: String, copyLabel: String): AddBookResult =
        AddBookResult.Failure(AddBookFailure.SAVE)

    suspend fun lookupBookstore(rawIsbn: String): BookstoreLookupResult =
        BookstoreLookupResult.Failure(AddBookFailure.SAVE)

    suspend fun changePurchaseState(
        book: BookstoreBook,
        transition: PurchaseTransition,
    ): BookstoreChangeResult = BookstoreChangeResult.Failure

    suspend fun updateBook(copyId: String, draft: BookEditDraft): UpdateBookResult

    suspend fun moveBookWithinTier(
        copyId: String,
        direction: ShelfMoveDirection,
    ): ShelfMoveResult = ShelfMoveResult.Failure

    suspend fun restoreBook(previous: LibraryBook, expectedCurrent: LibraryBook): Boolean

    suspend fun deleteBook(copyId: String): DeleteBookResult

    suspend fun restoreDeletedBook(book: LibraryBook): RestoreDeletedBookResult

    suspend fun previewImport(
        batch: LibraryImportBatch,
        conflictPolicy: ImportConflictPolicy,
    ): ImportPreviewResult

    suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult
}

/**
 * fake・簡易実装向けの既定フィルタ。`tagIds`は[LibraryBook]が所属タグを保持しないため
 * ここでは絞り込めず、Roomクエリを持つ実装（[dev.ndcshelf.app.data.repository.DefaultLibraryRepository]）
 * だけが対応する。
 */
private fun List<LibraryBook>.applyLibrarySearch(criteria: LibrarySearchCriteria): List<LibraryBook> {
    if (criteria.selectedEditionId != null) return this
    val filtered = filter { book ->
        (criteria.normalizedQuery.isEmpty() || book.matches(criteria.normalizedQuery)) &&
            (criteria.readingStatus == null || book.readingStatus == criteria.readingStatus) &&
            (criteria.ndcTopClass == null || book.ndcCode?.firstOrNull()?.digitToIntOrNull() == criteria.ndcTopClass) &&
            (
                criteria.locationQuery.isNullOrEmpty() ||
                    book.location.contains(criteria.locationQuery, ignoreCase = true)
            ) &&
            (criteria.addedAfterMillis == null || book.addedAt >= criteria.addedAfterMillis) &&
            (criteria.addedBeforeMillis == null || book.addedAt < criteria.addedBeforeMillis)
    }
    return when (criteria.sort) {
        LibrarySort.ADDED_NEWEST -> filtered.sortedWith(
            compareByDescending<LibraryBook>(LibraryBook::addedAt).thenBy(LibraryBook::copyId),
        )
        LibrarySort.TITLE -> filtered.sortedWith(
            compareBy<LibraryBook> { it.title.lowercase() }
                .thenByDescending(LibraryBook::addedAt)
                .thenBy(LibraryBook::copyId),
        )
        LibrarySort.AUTHOR -> filtered.sortedWith(
            compareBy<LibraryBook> { it.primaryAuthor.lowercase() }
                .thenBy { it.title.lowercase() }
                .thenBy(LibraryBook::copyId),
        )
        LibrarySort.NDC -> filtered.sortedWith(
            compareBy<LibraryBook> { it.ndcCode == null }
                .thenBy { it.ndcCode }
                .thenBy { it.title.lowercase() }
                .thenBy(LibraryBook::copyId),
        )
        LibrarySort.SHELF -> filtered.sortedWith(
            compareBy<LibraryBook> { it.locationTierId == null }
                .thenBy { it.location }
                .thenBy { it.shelfOrderKey == null }
                .thenBy { it.shelfOrderKey }
                .thenBy(LibraryBook::addedAt)
                .thenBy(LibraryBook::copyId),
        )
    }
}

sealed interface ManualBookResult {
    data class Added(val book: LibraryBook) : ManualBookResult
    data class Duplicate(
        val isbn13: String,
        val title: String,
        val copyCount: Int,
    ) : ManualBookResult
    data class Invalid(val errors: List<ManualBookValidationError>) : ManualBookResult
    data object Failure : ManualBookResult
}

sealed interface ManualReconciliationLookupResult {
    data class Ready(val preview: ManualReconciliationPreview) : ManualReconciliationLookupResult
    data class InvalidIsbn(val rawValue: String) : ManualReconciliationLookupResult
    data class NotFound(val isbn13: String) : ManualReconciliationLookupResult
    data class Failure(val reason: AddBookFailure) : ManualReconciliationLookupResult
    data object NotManual : ManualReconciliationLookupResult
}

sealed interface ManualReconciliationApplyResult {
    data object Applied : ManualReconciliationApplyResult
    data object Conflict : ManualReconciliationApplyResult
    data object Failure : ManualReconciliationApplyResult
}

sealed interface ScanUndoResult {
    data class Undone(val count: Int) : ScanUndoResult
    data object Conflict : ScanUndoResult
    data object NotFound : ScanUndoResult
    data object Failure : ScanUndoResult
}

sealed interface BookstoreLookupResult {
    data class Found(val book: BookstoreBook) : BookstoreLookupResult
    data class InvalidIsbn(val rawValue: String) : BookstoreLookupResult
    data class NotFound(val isbn13: String) : BookstoreLookupResult
    data class Failure(val reason: AddBookFailure, val isbn13: String? = null) : BookstoreLookupResult
}

sealed interface BookstoreChangeResult {
    data class Updated(val book: BookstoreBook) : BookstoreChangeResult
    data object Conflict : BookstoreChangeResult
    data object Failure : BookstoreChangeResult
}

enum class ShelfMoveDirection { LEFT, RIGHT }

sealed interface ShelfMoveResult {
    data object Moved : ShelfMoveResult
    data object Boundary : ShelfMoveResult
    data object NotFound : ShelfMoveResult
    data object Failure : ShelfMoveResult
}

sealed interface DeleteBookResult {
    data class Deleted(val book: LibraryBook) : DeleteBookResult

    data object NotFound : DeleteBookResult

    data object Failure : DeleteBookResult
}

sealed interface RestoreDeletedBookResult {
    data object Restored : RestoreDeletedBookResult

    data object Conflict : RestoreDeletedBookResult

    data object Failure : RestoreDeletedBookResult
}

sealed interface UpdateBookResult {
    data class Updated(
        val previous: LibraryBook,
        val current: LibraryBook,
    ) : UpdateBookResult

    data class Invalid(val errors: List<BookEditValidationError>) : UpdateBookResult

    data object NotFound : UpdateBookResult

    data object Failure : UpdateBookResult
}

sealed interface AddBookResult {
    data class Added(val book: LibraryBook) : AddBookResult

    data class Duplicate(val book: LibraryBook, val copyCount: Int = 1) : AddBookResult

    data class InvalidIsbn(val rawValue: String) : AddBookResult

    data class NotFound(val isbn13: String) : AddBookResult

    data class Failure(
        val reason: AddBookFailure,
        val isbn13: String? = null,
    ) : AddBookResult
}

enum class AddBookFailure(val retryable: Boolean) {
    OFFLINE(true),
    TIMEOUT(true),
    RATE_LIMITED(true),
    SERVICE_UNAVAILABLE(true),
    NETWORK(true),
    REQUEST_REJECTED(false),
    INVALID_RESPONSE(false),
    SAVE(false),
}
