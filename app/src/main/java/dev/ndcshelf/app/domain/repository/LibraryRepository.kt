package dev.ndcshelf.app.domain.repository

import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.BookEditValidationError
import dev.ndcshelf.app.domain.model.LibraryBook
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun observeLibrary(): Flow<List<LibraryBook>>

    suspend fun addFromIsbn(rawIsbn: String): AddBookResult

    suspend fun updateBook(copyId: String, draft: BookEditDraft): UpdateBookResult

    suspend fun restoreBook(previous: LibraryBook, expectedCurrent: LibraryBook): Boolean

    suspend fun previewImport(
        batch: LibraryImportBatch,
        conflictPolicy: ImportConflictPolicy,
    ): ImportPreviewResult

    suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult
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

    data class Duplicate(val book: LibraryBook) : AddBookResult

    data class InvalidIsbn(val rawValue: String) : AddBookResult

    data class NotFound(val isbn13: String) : AddBookResult

    data class Failure(val message: String) : AddBookResult
}
