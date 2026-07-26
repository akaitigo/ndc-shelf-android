package dev.ndcshelf.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.ndcshelf.app.domain.backup.DatabaseBackupFailure
import dev.ndcshelf.app.domain.backup.DatabaseBackupInspectResult
import dev.ndcshelf.app.domain.backup.DatabaseBackupManager
import dev.ndcshelf.app.domain.backup.DatabaseBackupMetadata
import dev.ndcshelf.app.domain.backup.DatabaseBackupPreview
import dev.ndcshelf.app.domain.backup.DatabaseRestoreResult
import dev.ndcshelf.app.domain.export.LibraryExportFormat
import dev.ndcshelf.app.domain.export.LibraryExporter
import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.ImportValidationError
import dev.ndcshelf.app.domain.importer.LibraryCsvImporter
import dev.ndcshelf.app.domain.importer.LibraryCsvParseResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.importer.LibraryJsonImporter
import dev.ndcshelf.app.domain.importer.LibraryJsonParseResult
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.BookEditValidationError
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class MainViewModel(
    private val repository: LibraryRepository,
    private val importIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val importComputationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val databaseBackupManager: DatabaseBackupManager? = null,
) : ViewModel() {
    val books: StateFlow<List<LibraryBook>> = repository.observeLibrary()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()
    private val _importState = MutableStateFlow<LibraryImportUiState>(LibraryImportUiState.Idle)
    val importState: StateFlow<LibraryImportUiState> = _importState.asStateFlow()
    private val _bookEditState = MutableStateFlow<BookEditUiState>(BookEditUiState.Idle)
    val bookEditState: StateFlow<BookEditUiState> = _bookEditState.asStateFlow()
    private val _bookDeleteState = MutableStateFlow<BookDeleteUiState>(BookDeleteUiState.Idle)
    val bookDeleteState: StateFlow<BookDeleteUiState> = _bookDeleteState.asStateFlow()
    private val _databaseBackupState = MutableStateFlow<DatabaseBackupUiState>(DatabaseBackupUiState.Idle)
    val databaseBackupState: StateFlow<DatabaseBackupUiState> = _databaseBackupState.asStateFlow()
    private val _libraryExportState = MutableStateFlow<LibraryExportUiState>(LibraryExportUiState.Idle)
    val libraryExportState: StateFlow<LibraryExportUiState> = _libraryExportState.asStateFlow()

    private var lastSubmission: Pair<String, Long>? = null
    private val jsonImporter = LibraryJsonImporter()
    private val csvImporter = LibraryCsvImporter()
    private var importBatch: LibraryImportBatch? = null
    private var importPreview: LibraryImportPreview? = null
    private var importWarnings: List<ImportValidationError> = emptyList()
    private var importJob: Job? = null
    private var databaseBackupPreview: DatabaseBackupPreview? = null
    private var databaseBackupJob: Job? = null
    private var exportJob: Job? = null

    fun exportLibrary(format: LibraryExportFormat, output: OutputStream) {
        if (_libraryExportState.value === LibraryExportUiState.Exporting) {
            output.closeSilently()
            return
        }
        val booksToExport = books.value.toList()
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _libraryExportState.value = LibraryExportUiState.Exporting
            try {
                withContext(importIoDispatcher) {
                    output.use { LibraryExporter.write(booksToExport, format, it) }
                }
                _libraryExportState.value = LibraryExportUiState.Success(booksToExport.size)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _libraryExportState.value = LibraryExportUiState.Error
            }
        }
    }

    fun consumeLibraryExportResult() {
        if (_libraryExportState.value is LibraryExportUiState.Success ||
            _libraryExportState.value === LibraryExportUiState.Error
        ) {
            _libraryExportState.value = LibraryExportUiState.Idle
        }
    }

    fun createDatabaseBackup(output: OutputStream) {
        val manager = databaseBackupManager ?: run {
            output.closeSilently()
            return
        }
        if (_databaseBackupState.value.isBusy) {
            output.closeSilently()
            return
        }
        databaseBackupJob?.cancel()
        databaseBackupJob = viewModelScope.launch {
            _databaseBackupState.value = DatabaseBackupUiState.Creating
            val result = withContext(importIoDispatcher) { manager.createBackup(output) }
            _databaseBackupState.value = when (result) {
                is dev.ndcshelf.app.domain.backup.DatabaseBackupCreateResult.Success -> {
                    DatabaseBackupUiState.Created(result.metadata.copyCount)
                }
                is dev.ndcshelf.app.domain.backup.DatabaseBackupCreateResult.Failure -> {
                    DatabaseBackupUiState.Error(result.reason)
                }
            }
        }
    }

    fun loadDatabaseBackup(input: InputStream) {
        val manager = databaseBackupManager ?: run {
            input.closeSilently()
            return
        }
        if (_databaseBackupState.value.isBusy) {
            input.closeSilently()
            return
        }
        databaseBackupPreview = null
        databaseBackupJob?.cancel()
        databaseBackupJob = viewModelScope.launch {
            _databaseBackupState.value = DatabaseBackupUiState.Inspecting
            val result = withContext(importIoDispatcher) { manager.inspectBackup(input) }
            _databaseBackupState.value = when (result) {
                is DatabaseBackupInspectResult.Valid -> {
                    databaseBackupPreview = result.preview
                    DatabaseBackupUiState.Preview(result.preview.metadata)
                }
                is DatabaseBackupInspectResult.Invalid -> DatabaseBackupUiState.Error(result.reason)
            }
        }
    }

    fun confirmDatabaseRestore() {
        val manager = databaseBackupManager ?: return
        val preview = databaseBackupPreview ?: return
        if (_databaseBackupState.value !is DatabaseBackupUiState.Preview) return
        databaseBackupJob?.cancel()
        databaseBackupJob = viewModelScope.launch {
            _databaseBackupState.value = DatabaseBackupUiState.Restoring
            val result = withContext(importIoDispatcher) { manager.restoreBackup(preview) }
            _databaseBackupState.value = when (result) {
                is DatabaseRestoreResult.Success -> {
                    databaseBackupPreview = null
                    DatabaseBackupUiState.Restored(
                        restoredCopyCount = result.restoredCopyCount,
                        automaticBackupName = result.automaticBackupName,
                    )
                }
                is DatabaseRestoreResult.Failure -> DatabaseBackupUiState.Error(result.reason)
            }
        }
    }

    fun dismissDatabaseBackup() {
        if (_databaseBackupState.value.isBusy) return
        databaseBackupPreview = null
        _databaseBackupState.value = DatabaseBackupUiState.Idle
    }

    fun submitIsbn(rawIsbn: String) {
        if (_scanState.value is ScanUiState.Loading) return

        val now = System.currentTimeMillis()
        val previous = lastSubmission
        if (
            previous != null &&
            previous.first == rawIsbn &&
            now - previous.second < RESCAN_GUARD_MILLIS
        ) {
            return
        }
        lastSubmission = rawIsbn to now

        viewModelScope.launch {
            _scanState.value = ScanUiState.Loading(rawIsbn)
            _scanState.value = when (val result = repository.addFromIsbn(rawIsbn)) {
                is AddBookResult.Added -> ScanUiState.Added(
                    isbn13 = result.book.isbn13,
                    title = result.book.title,
                )

                is AddBookResult.Duplicate -> ScanUiState.Duplicate(
                    isbn13 = result.book.isbn13,
                    title = result.book.title,
                )

                is AddBookResult.InvalidIsbn -> ScanUiState.Error(
                    "ISBNの形式またはチェックデジットが正しくありません",
                )

                is AddBookResult.NotFound -> ScanUiState.Error(
                    "国立国会図書館サーチで ${result.isbn13} が見つかりませんでした",
                )

                is AddBookResult.Failure -> ScanUiState.Error(result.message)
            }
        }
    }

    fun reportCameraError(message: String) {
        if (_scanState.value !is ScanUiState.Loading) {
            _scanState.value = ScanUiState.Error(message)
        }
    }

    fun clearScanState() {
        _scanState.value = ScanUiState.Idle
    }

    fun saveBookEdit(copyId: String, draft: BookEditDraft) {
        if (_bookEditState.value is BookEditUiState.Saving) return
        viewModelScope.launch {
            _bookEditState.value = BookEditUiState.Saving(copyId)
            _bookEditState.value = when (val result = repository.updateBook(copyId, draft)) {
                is UpdateBookResult.Updated -> BookEditUiState.Saved(
                    previous = result.previous,
                    current = result.current,
                )
                is UpdateBookResult.Invalid -> BookEditUiState.Invalid(copyId, result.errors)
                UpdateBookResult.NotFound -> BookEditUiState.Error(copyId, BookEditFailure.NOT_FOUND)
                UpdateBookResult.Failure -> BookEditUiState.Error(copyId, BookEditFailure.SAVE)
            }
        }
    }

    fun undoLastBookEdit() {
        val saved = _bookEditState.value as? BookEditUiState.Saved ?: return
        viewModelScope.launch {
            _bookEditState.value = BookEditUiState.Undoing
            _bookEditState.value = if (
                repository.restoreBook(saved.previous, saved.current)
            ) {
                BookEditUiState.Undone
            } else {
                BookEditUiState.Error(saved.current.copyId, BookEditFailure.UNDO)
            }
        }
    }

    fun clearBookEditState() {
        if (_bookEditState.value !is BookEditUiState.Saving &&
            _bookEditState.value !is BookEditUiState.Undoing
        ) {
            _bookEditState.value = BookEditUiState.Idle
        }
    }

    fun deleteBook(copyId: String) {
        if (_bookDeleteState.value is BookDeleteUiState.Deleting ||
            _bookDeleteState.value is BookDeleteUiState.Restoring
        ) {
            return
        }
        viewModelScope.launch {
            _bookDeleteState.value = BookDeleteUiState.Deleting(copyId)
            _bookDeleteState.value = when (val result = repository.deleteBook(copyId)) {
                is DeleteBookResult.Deleted -> BookDeleteUiState.Deleted(result.book)
                DeleteBookResult.NotFound -> BookDeleteUiState.Error(BookDeleteFailure.NOT_FOUND)
                DeleteBookResult.Failure -> BookDeleteUiState.Error(BookDeleteFailure.DELETE)
            }
        }
    }

    fun undoLastBookDeletion() {
        val deleted = _bookDeleteState.value as? BookDeleteUiState.Deleted ?: return
        viewModelScope.launch {
            _bookDeleteState.value = BookDeleteUiState.Restoring
            _bookDeleteState.value = when (repository.restoreDeletedBook(deleted.book)) {
                RestoreDeletedBookResult.Restored -> BookDeleteUiState.Restored
                RestoreDeletedBookResult.Conflict -> {
                    BookDeleteUiState.Error(BookDeleteFailure.RESTORE_CONFLICT)
                }
                RestoreDeletedBookResult.Failure -> {
                    BookDeleteUiState.Error(BookDeleteFailure.RESTORE)
                }
            }
        }
    }

    fun clearBookDeleteState() {
        if (_bookDeleteState.value !is BookDeleteUiState.Deleting &&
            _bookDeleteState.value !is BookDeleteUiState.Restoring
        ) {
            _bookDeleteState.value = BookDeleteUiState.Idle
        }
    }

    fun loadJsonImport(input: InputStream) {
        importJob?.cancel()
        importBatch = null
        importPreview = null
        importJob = viewModelScope.launch {
            _importState.value = LibraryImportUiState.Loading
            try {
                when (
                    val parsed = withContext(importIoDispatcher) {
                        input.use { stream -> jsonImporter.parse(stream) }
                    }
                ) {
                    is LibraryJsonParseResult.Invalid -> {
                        _importState.value = LibraryImportUiState.Invalid(parsed.errors)
                    }

                    is LibraryJsonParseResult.Valid -> {
                        importBatch = parsed.batch
                        importWarnings = emptyList()
                        previewImport(parsed.batch, ImportConflictPolicy.SKIP_EXISTING)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _importState.value = LibraryImportUiState.Error(ImportFailure.JSON_READ)
            }
        }
    }

    fun loadCsvImport(input: InputStream) {
        importJob?.cancel()
        importBatch = null
        importPreview = null
        importWarnings = emptyList()
        importJob = viewModelScope.launch {
            _importState.value = LibraryImportUiState.Loading
            try {
                when (
                    val parsed = withContext(importIoDispatcher) {
                        input.use { stream -> csvImporter.parse(stream) }
                    }
                ) {
                    is LibraryCsvParseResult.Invalid -> {
                        _importState.value = LibraryImportUiState.Invalid(parsed.errors)
                    }

                    is LibraryCsvParseResult.Valid -> {
                        importBatch = parsed.batch
                        importWarnings = parsed.warnings
                        previewImport(parsed.batch, ImportConflictPolicy.SKIP_EXISTING)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _importState.value = LibraryImportUiState.Error(ImportFailure.CSV_READ)
            }
        }
    }

    fun selectImportConflictPolicy(policy: ImportConflictPolicy) {
        val batch = importBatch ?: return
        val current = _importState.value as? LibraryImportUiState.Preview
        if (current?.conflictPolicy == policy) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _importState.value = LibraryImportUiState.Loading
            try {
                previewImport(batch, policy)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _importState.value = LibraryImportUiState.Error(ImportFailure.PREVIEW)
            }
        }
    }

    fun confirmImport() {
        val preview = importPreview ?: return
        if (_importState.value !is LibraryImportUiState.Preview) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _importState.value = LibraryImportUiState.Applying
            when (val result = repository.applyImport(preview)) {
                is ImportApplyResult.Applied -> {
                    clearPreparedImport()
                    _importState.value = LibraryImportUiState.Success(
                        addedCount = result.addedCount,
                        updatedCount = result.updatedCount,
                        skippedCount = result.skippedCount,
                    )
                }

                is ImportApplyResult.Failure -> {
                    _importState.value = LibraryImportUiState.Error(ImportFailure.APPLY)
                }

                ImportApplyResult.StalePreview -> {
                    val batch = importBatch
                    if (batch == null) {
                        _importState.value = LibraryImportUiState.Error(
                            ImportFailure.STALE_RESELECT,
                        )
                    } else {
                        previewImport(
                            batch = batch,
                            policy = preview.conflictPolicy,
                            staleRecalculated = true,
                        )
                    }
                }
            }
        }
    }

    fun dismissImport() {
        importJob?.cancel()
        clearPreparedImport()
        _importState.value = LibraryImportUiState.Idle
    }

    fun consumeImportSuccess() {
        if (_importState.value is LibraryImportUiState.Success) {
            _importState.value = LibraryImportUiState.Idle
        }
    }

    private suspend fun previewImport(
        batch: LibraryImportBatch,
        policy: ImportConflictPolicy,
        staleRecalculated: Boolean = false,
    ) {
        when (
            val result = withContext(importComputationDispatcher) {
                repository.previewImport(batch, policy)
            }
        ) {
            is ImportPreviewResult.Invalid -> {
                importPreview = null
                _importState.value = LibraryImportUiState.Invalid(result.errors)
            }

            is ImportPreviewResult.Valid -> {
                importPreview = result.preview
                _importState.value = LibraryImportUiState.Preview(
                    addedCount = result.preview.additions.size,
                    updatedCount = result.preview.updates.size,
                    skippedCount = result.preview.skippedCount,
                    warnings = importWarnings,
                    conflictPolicy = result.preview.conflictPolicy,
                    staleRecalculated = staleRecalculated,
                )
            }
        }
    }

    private fun clearPreparedImport() {
        importBatch = null
        importPreview = null
        importWarnings = emptyList()
    }

    companion object {
        private const val RESCAN_GUARD_MILLIS = 4_000L

        fun factory(
            repository: LibraryRepository,
            databaseBackupManager: DatabaseBackupManager,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java))
                    return MainViewModel(
                        repository = repository,
                        databaseBackupManager = databaseBackupManager,
                    ) as T
                }
            }
    }
}

sealed interface DatabaseBackupUiState {
    data object Idle : DatabaseBackupUiState
    data object Creating : DatabaseBackupUiState
    data class Created(val copyCount: Int) : DatabaseBackupUiState
    data object Inspecting : DatabaseBackupUiState
    data class Preview(val metadata: DatabaseBackupMetadata) : DatabaseBackupUiState
    data object Restoring : DatabaseBackupUiState
    data class Restored(
        val restoredCopyCount: Int,
        val automaticBackupName: String,
    ) : DatabaseBackupUiState
    data class Error(val failure: DatabaseBackupFailure) : DatabaseBackupUiState
}

sealed interface LibraryExportUiState {
    data object Idle : LibraryExportUiState
    data object Exporting : LibraryExportUiState
    data class Success(val bookCount: Int) : LibraryExportUiState
    data object Error : LibraryExportUiState
}

private val DatabaseBackupUiState.isBusy: Boolean
    get() = this === DatabaseBackupUiState.Creating ||
        this === DatabaseBackupUiState.Inspecting ||
        this === DatabaseBackupUiState.Restoring

private fun java.io.Closeable.closeSilently() {
    runCatching(::close)
}

sealed interface ScanUiState {
    data object Idle : ScanUiState

    data class Loading(val isbn: String) : ScanUiState

    data class Added(
        val isbn13: String,
        val title: String,
    ) : ScanUiState

    data class Duplicate(
        val isbn13: String,
        val title: String,
    ) : ScanUiState

    data class Error(val message: String) : ScanUiState
}

sealed interface LibraryImportUiState {
    data object Idle : LibraryImportUiState

    data object Loading : LibraryImportUiState

    data object Applying : LibraryImportUiState

    data class Invalid(val errors: List<ImportValidationError>) : LibraryImportUiState

    data class Preview(
        val addedCount: Int,
        val updatedCount: Int,
        val skippedCount: Int,
        val warnings: List<ImportValidationError> = emptyList(),
        val conflictPolicy: ImportConflictPolicy,
        val staleRecalculated: Boolean = false,
    ) : LibraryImportUiState {
        val changeCount: Int = addedCount + updatedCount
    }

    data class Success(
        val addedCount: Int,
        val updatedCount: Int,
        val skippedCount: Int,
    ) : LibraryImportUiState

    data class Error(val failure: ImportFailure) : LibraryImportUiState
}

enum class ImportFailure {
    JSON_READ,
    CSV_READ,
    PREVIEW,
    APPLY,
    STALE_RESELECT,
}

sealed interface BookEditUiState {
    data object Idle : BookEditUiState

    data class Saving(val copyId: String) : BookEditUiState

    data class Invalid(
        val copyId: String,
        val errors: List<BookEditValidationError>,
    ) : BookEditUiState

    data class Saved(
        val previous: LibraryBook,
        val current: LibraryBook,
    ) : BookEditUiState

    data object Undoing : BookEditUiState

    data object Undone : BookEditUiState

    data class Error(val copyId: String, val failure: BookEditFailure) : BookEditUiState
}

enum class BookEditFailure {
    NOT_FOUND,
    SAVE,
    UNDO,
}

sealed interface BookDeleteUiState {
    data object Idle : BookDeleteUiState

    data class Deleting(val copyId: String) : BookDeleteUiState

    data class Deleted(val book: LibraryBook) : BookDeleteUiState

    data object Restoring : BookDeleteUiState

    data object Restored : BookDeleteUiState

    data class Error(val failure: BookDeleteFailure) : BookDeleteUiState
}

enum class BookDeleteFailure {
    NOT_FOUND,
    DELETE,
    RESTORE_CONFLICT,
    RESTORE,
}
