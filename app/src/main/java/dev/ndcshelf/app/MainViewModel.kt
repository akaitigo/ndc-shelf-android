package dev.ndcshelf.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
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

class MainViewModel(
    private val repository: LibraryRepository,
    private val importIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val importComputationDispatcher: CoroutineDispatcher = Dispatchers.Default,
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

    private var lastSubmission: Pair<String, Long>? = null
    private val jsonImporter = LibraryJsonImporter()
    private val csvImporter = LibraryCsvImporter()
    private var importBatch: LibraryImportBatch? = null
    private var importPreview: LibraryImportPreview? = null
    private var importWarnings: List<ImportValidationError> = emptyList()
    private var importJob: Job? = null

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

    fun updateCopy(
        copyId: String,
        location: String,
        status: ReadingStatus,
    ) {
        viewModelScope.launch {
            repository.updateCopy(copyId, location, status)
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

        fun factory(repository: LibraryRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java))
                    return MainViewModel(repository) as T
                }
            }
    }
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
