package dev.ndcshelf.app.domain.repository

import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.ReadingStatus
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun observeLibrary(): Flow<List<LibraryBook>>

    suspend fun addFromIsbn(rawIsbn: String): AddBookResult

    suspend fun updateCopy(
        copyId: String,
        location: String,
        readingStatus: ReadingStatus,
    )

    suspend fun previewImport(
        batch: LibraryImportBatch,
        conflictPolicy: ImportConflictPolicy,
    ): ImportPreviewResult

    suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult
}

sealed interface AddBookResult {
    data class Added(val book: LibraryBook) : AddBookResult

    data class Duplicate(val book: LibraryBook) : AddBookResult

    data class InvalidIsbn(val rawValue: String) : AddBookResult

    data class NotFound(val isbn13: String) : AddBookResult

    data class Failure(val message: String) : AddBookResult
}
