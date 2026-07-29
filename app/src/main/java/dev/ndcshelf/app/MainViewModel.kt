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
import dev.ndcshelf.app.domain.model.BookstoreBook
import dev.ndcshelf.app.domain.model.BookEditValidationError
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibrarySearchSettingsStore
import dev.ndcshelf.app.domain.model.LibrarySort
import dev.ndcshelf.app.domain.model.LibraryStats
import dev.ndcshelf.app.domain.model.InMemoryLibrarySearchSettingsStore
import dev.ndcshelf.app.domain.model.LocationLevel
import dev.ndcshelf.app.domain.model.LocationMutationResult
import dev.ndcshelf.app.domain.model.LocationTree
import dev.ndcshelf.app.domain.model.ManualBookDraft
import dev.ndcshelf.app.domain.model.ManualBookValidationError
import dev.ndcshelf.app.domain.model.ManualReconciliationPreview
import dev.ndcshelf.app.domain.model.MAX_LIBRARY_QUERY_LENGTH
import dev.ndcshelf.app.domain.model.PurchaseTransition
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.ScanSession
import dev.ndcshelf.app.domain.model.SeriesOverview
import dev.ndcshelf.app.domain.model.SeriesSuggestion
import dev.ndcshelf.app.domain.model.WorkVariantEditor
import dev.ndcshelf.app.domain.model.MoveDirection
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.BookstoreChangeResult
import dev.ndcshelf.app.domain.repository.BookstoreLookupResult
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.LocationRepository
import dev.ndcshelf.app.domain.repository.ManualBookResult
import dev.ndcshelf.app.domain.repository.ManualReconciliationApplyResult
import dev.ndcshelf.app.domain.repository.ManualReconciliationLookupResult
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.ShelfMoveDirection
import dev.ndcshelf.app.domain.repository.ShelfMoveResult
import dev.ndcshelf.app.domain.repository.ScanUndoResult
import dev.ndcshelf.app.domain.repository.SeriesRepository
import dev.ndcshelf.app.domain.repository.SeriesWatchMutationResult
import dev.ndcshelf.app.domain.repository.SeriesWatchRepository
import dev.ndcshelf.app.domain.repository.SeriesWatchScheduler
import dev.ndcshelf.app.domain.repository.SeriesConfirmationDraft
import dev.ndcshelf.app.domain.repository.SeriesConfirmationResult
import dev.ndcshelf.app.domain.repository.SeriesConfirmationTarget
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import dev.ndcshelf.app.domain.repository.WorkGroupMutationResult
import dev.ndcshelf.app.domain.repository.WorkGroupRepository
import dev.ndcshelf.app.domain.sync.SyncEngineStatus
import dev.ndcshelf.app.domain.sync.SyncStatusRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(
    private val repository: LibraryRepository,
    private val importIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val importComputationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val databaseBackupManager: DatabaseBackupManager? = null,
    private val locationRepository: LocationRepository? = null,
    private val librarySearchSettings: LibrarySearchSettingsStore =
        InMemoryLibrarySearchSettingsStore,
    private val seriesRepository: SeriesRepository? = null,
    private val workGroupRepository: WorkGroupRepository? = null,
    private val seriesWatchRepository: SeriesWatchRepository? = null,
    private val seriesWatchScheduler: SeriesWatchScheduler? = null,
    syncStatusRepository: SyncStatusRepository? = null,
) : ViewModel() {
    init {
        if (seriesWatchRepository != null && seriesWatchScheduler != null) {
            viewModelScope.launch {
                runCatching {
                    seriesWatchScheduler.reconcile(seriesWatchRepository.hasEnabledWatches())
                }
            }
        }
    }

    val books: StateFlow<List<LibraryBook>> = repository.observeLibrary()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
    private val _librarySearchCriteria = MutableStateFlow(librarySearchSettings.load())
    val librarySearchCriteria: StateFlow<LibrarySearchCriteria> = _librarySearchCriteria.asStateFlow()
    val librarySearchResult: StateFlow<LibrarySearchResult> = _librarySearchCriteria
        .debounce { criteria -> if (criteria.selectedEditionId == null) SEARCH_DEBOUNCE_MILLIS else 0L }
        .distinctUntilChanged()
        .onEach { criteria ->
            if (criteria.selectedEditionId == null) librarySearchSettings.save(criteria)
        }
        .flatMapLatest { criteria ->
            repository.observeLibrary(criteria).map { books -> LibrarySearchResult(criteria, books) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibrarySearchResult(LibrarySearchCriteria(), emptyList()),
        )
    val libraryStats: StateFlow<LibraryStats> = repository.observeLibraryStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryStats())
    val wishlist: StateFlow<List<BookstoreBook>> = repository.observeWishlist()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
    val scanSessions: StateFlow<List<ScanSession>> = repository.observeScanSessions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
    val locations: StateFlow<LocationTree> = (locationRepository?.observeTree() ?: flowOf(LocationTree()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationTree())
    val seriesCatalog: StateFlow<List<SeriesOverview>> =
        (seriesRepository?.observeCatalog() ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val seriesSuggestions: StateFlow<List<SeriesSuggestion>> =
        (seriesRepository?.observeSuggestions() ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val seriesWatches = (seriesWatchRepository?.observeWatches() ?: flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val syncStatus: StateFlow<SyncEngineStatus> =
        (syncStatusRepository?.observeStatus() ?: flowOf(SyncEngineStatus()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncEngineStatus())
    private val _seriesWatchMutationState =
        MutableStateFlow<SeriesWatchMutationUiState>(SeriesWatchMutationUiState.Idle)
    val seriesWatchMutationState = _seriesWatchMutationState.asStateFlow()
    private val _seriesEditorState = MutableStateFlow<SeriesEditorUiState>(SeriesEditorUiState.Idle)
    val seriesEditorState: StateFlow<SeriesEditorUiState> = _seriesEditorState.asStateFlow()
    private val _workVariantState = MutableStateFlow<WorkVariantUiState>(WorkVariantUiState.Idle)
    val workVariantState: StateFlow<WorkVariantUiState> = _workVariantState.asStateFlow()

    private val _scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()
    private val _scanSessionState = MutableStateFlow<ScanSessionUiState>(ScanSessionUiState.Idle)
    val scanSessionState: StateFlow<ScanSessionUiState> = _scanSessionState.asStateFlow()
    private val _manualRegistrationState =
        MutableStateFlow<ManualRegistrationUiState>(ManualRegistrationUiState.Idle)
    val manualRegistrationState: StateFlow<ManualRegistrationUiState> =
        _manualRegistrationState.asStateFlow()
    private val _manualReconciliationState =
        MutableStateFlow<ManualReconciliationUiState>(ManualReconciliationUiState.Idle)
    val manualReconciliationState: StateFlow<ManualReconciliationUiState> =
        _manualReconciliationState.asStateFlow()
    private val _bookstoreState = MutableStateFlow<BookstoreUiState>(BookstoreUiState.Idle)
    val bookstoreState: StateFlow<BookstoreUiState> = _bookstoreState.asStateFlow()
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
    private val _locationMutationState = MutableStateFlow<LocationMutationUiState>(LocationMutationUiState.Idle)
    val locationMutationState: StateFlow<LocationMutationUiState> = _locationMutationState.asStateFlow()
    private val _shelfMoveState = MutableStateFlow<ShelfMoveUiState>(ShelfMoveUiState.Idle)
    val shelfMoveState: StateFlow<ShelfMoveUiState> = _shelfMoveState.asStateFlow()

    private var lastSubmission: Pair<String, Long>? = null
    private var lastBookstoreSubmission: Pair<String, Long>? = null
    private var activeScanSessionId: String? = null
    private val jsonImporter = LibraryJsonImporter()
    private val csvImporter = LibraryCsvImporter()
    private var importBatch: LibraryImportBatch? = null
    private var importPreview: LibraryImportPreview? = null
    private var importWarnings: List<ImportValidationError> = emptyList()
    private var importJob: Job? = null
    private var databaseBackupPreview: DatabaseBackupPreview? = null
    private var databaseBackupJob: Job? = null
    private var exportJob: Job? = null
    private var seriesJob: Job? = null
    private var workVariantJob: Job? = null
    private var activeWorkVariantId: String? = null

    fun prepareWorkVariantEditor(workId: String) {
        val source = workGroupRepository ?: return
        activeWorkVariantId = workId
        workVariantJob?.cancel()
        workVariantJob = viewModelScope.launch {
            _workVariantState.value = WorkVariantUiState.Loading
            _workVariantState.value = source.editorFor(workId)
                ?.let(WorkVariantUiState::Ready)
                ?: WorkVariantUiState.Error
        }
    }

    fun linkWorkVariant(targetWorkId: String, enableSeriesSubstitution: Boolean) {
        val source = workGroupRepository ?: return
        val editor = (_workVariantState.value as? WorkVariantUiState.Ready)?.editor ?: return
        val target = editor.suggestions.firstOrNull { it.work.workId == targetWorkId }?.work ?: return
        workVariantJob?.cancel()
        workVariantJob = viewModelScope.launch {
            _workVariantState.value = WorkVariantUiState.Saving
            when (
                source.link(
                    sourceWorkId = editor.source.workId,
                    targetWorkId = target.workId,
                    expectedSourceTitle = editor.source.title,
                    expectedTargetTitle = target.title,
                    seriesSubstitutionEnabled = enableSeriesSubstitution,
                )
            ) {
                is WorkGroupMutationResult.Linked -> reloadWorkVariantEditor(source)
                WorkGroupMutationResult.Conflict -> _workVariantState.value = WorkVariantUiState.Conflict
                WorkGroupMutationResult.Invalid -> _workVariantState.value = WorkVariantUiState.Invalid
                else -> _workVariantState.value = WorkVariantUiState.Error
            }
        }
    }

    fun unlinkWorkVariant(membershipId: String) {
        val source = workGroupRepository ?: return
        workVariantJob?.cancel()
        workVariantJob = viewModelScope.launch {
            _workVariantState.value = WorkVariantUiState.Saving
            when (source.unlink(membershipId)) {
                WorkGroupMutationResult.Unlinked -> reloadWorkVariantEditor(source)
                WorkGroupMutationResult.Conflict -> _workVariantState.value = WorkVariantUiState.Conflict
                WorkGroupMutationResult.Invalid -> _workVariantState.value = WorkVariantUiState.Invalid
                else -> _workVariantState.value = WorkVariantUiState.Error
            }
        }
    }

    fun setWorkVariantSeriesSubstitution(groupId: String, enabled: Boolean) {
        val source = workGroupRepository ?: return
        workVariantJob?.cancel()
        workVariantJob = viewModelScope.launch {
            _workVariantState.value = WorkVariantUiState.Saving
            when (source.setSeriesSubstitution(groupId, enabled)) {
                WorkGroupMutationResult.Updated -> reloadWorkVariantEditor(source)
                WorkGroupMutationResult.Conflict -> _workVariantState.value = WorkVariantUiState.Conflict
                WorkGroupMutationResult.Invalid -> _workVariantState.value = WorkVariantUiState.Invalid
                else -> _workVariantState.value = WorkVariantUiState.Error
            }
        }
    }

    fun clearWorkVariantState() {
        workVariantJob?.cancel()
        activeWorkVariantId = null
        _workVariantState.value = WorkVariantUiState.Idle
    }

    private suspend fun reloadWorkVariantEditor(source: WorkGroupRepository) {
        val workId = activeWorkVariantId
        _workVariantState.value = workId?.let { source.editorFor(it) }
            ?.let(WorkVariantUiState::Ready)
            ?: WorkVariantUiState.Error
    }

    fun prepareSeriesEditor(workId: String) {
        val source = seriesRepository ?: return
        seriesJob?.cancel()
        seriesJob = viewModelScope.launch {
            _seriesEditorState.value = SeriesEditorUiState.Loading
            _seriesEditorState.value = source.suggestionFor(workId)
                ?.let(SeriesEditorUiState::Ready)
                ?: SeriesEditorUiState.Error
        }
    }

    fun confirmSeries(
        target: SeriesConfirmationTarget,
        drafts: List<SeriesConfirmationDraft>,
    ) {
        val source = seriesRepository ?: return
        if (_seriesEditorState.value === SeriesEditorUiState.Saving) return
        seriesJob?.cancel()
        seriesJob = viewModelScope.launch {
            _seriesEditorState.value = SeriesEditorUiState.Saving
            _seriesEditorState.value = when (val result = source.confirm(target, drafts)) {
                is SeriesConfirmationResult.Confirmed ->
                    SeriesEditorUiState.Saved(result.seriesId, result.membershipIds.size)
                SeriesConfirmationResult.Conflict -> SeriesEditorUiState.Conflict
                SeriesConfirmationResult.Invalid -> SeriesEditorUiState.Invalid
                SeriesConfirmationResult.Failure -> SeriesEditorUiState.Error
            }
        }
    }

    fun removeSeriesMembership(membershipId: String) {
        val source = seriesRepository ?: return
        if (_seriesEditorState.value === SeriesEditorUiState.Saving) return
        seriesJob?.cancel()
        seriesJob = viewModelScope.launch {
            _seriesEditorState.value = SeriesEditorUiState.Saving
            _seriesEditorState.value = if (source.removeMembership(membershipId)) {
                SeriesEditorUiState.Removed
            } else {
                SeriesEditorUiState.Error
            }
        }
    }

    fun clearSeriesEditorState() {
        if (_seriesEditorState.value !== SeriesEditorUiState.Saving) {
            _seriesEditorState.value = SeriesEditorUiState.Idle
        }
    }

    fun setSeriesWatchEnabled(seriesId: String, enabled: Boolean) {
        val source = seriesWatchRepository ?: return
        if (_seriesWatchMutationState.value === SeriesWatchMutationUiState.Working) return
        viewModelScope.launch {
            _seriesWatchMutationState.value = SeriesWatchMutationUiState.Working
            _seriesWatchMutationState.value = when (source.setEnabled(seriesId, enabled)) {
                SeriesWatchMutationResult.Updated -> {
                    runCatching {
                        seriesWatchScheduler?.reconcile(source.hasEnabledWatches())
                    }.fold(
                        onSuccess = { SeriesWatchMutationUiState.Updated },
                        onFailure = { SeriesWatchMutationUiState.Error },
                    )
                }
                SeriesWatchMutationResult.Invalid -> SeriesWatchMutationUiState.Invalid
                SeriesWatchMutationResult.Failure -> SeriesWatchMutationUiState.Error
            }
        }
    }

    fun reportSeriesWatchPermissionDenied() {
        _seriesWatchMutationState.value = SeriesWatchMutationUiState.PermissionDenied
    }

    fun clearSeriesWatchMutationState() {
        if (_seriesWatchMutationState.value !== SeriesWatchMutationUiState.Working) {
            _seriesWatchMutationState.value = SeriesWatchMutationUiState.Idle
        }
    }

    fun exportLibrary(format: LibraryExportFormat, output: OutputStream) {
        if (_libraryExportState.value === LibraryExportUiState.Exporting) {
            output.closeSilently()
            return
        }
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _libraryExportState.value = LibraryExportUiState.Exporting
            try {
                val booksToExport = withContext(importIoDispatcher) {
                    output.use { stream ->
                        val snapshot = repository.getLibrarySnapshot()
                        LibraryExporter.write(snapshot, format, stream)
                        snapshot
                    }
                }
                _libraryExportState.value = LibraryExportUiState.Success(booksToExport.size)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _libraryExportState.value = LibraryExportUiState.Error
            }
        }
    }

    fun updateLibraryQuery(query: String) {
        _librarySearchCriteria.value = _librarySearchCriteria.value.copy(
            query = query.take(MAX_LIBRARY_QUERY_LENGTH),
        )
    }

    fun updateLibraryReadingStatus(status: ReadingStatus?) {
        _librarySearchCriteria.value = _librarySearchCriteria.value.copy(readingStatus = status)
    }

    fun updateLibrarySort(sort: LibrarySort) {
        _librarySearchCriteria.value = _librarySearchCriteria.value.copy(sort = sort)
    }

    fun selectLibraryEdition(editionId: String?) {
        _librarySearchCriteria.value = _librarySearchCriteria.value.copy(selectedEditionId = editionId)
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
            val result = repository.addFromIsbn(rawIsbn)
            recordCurrentScanAttempt(rawIsbn, result)
            _scanState.value = when (result) {
                is AddBookResult.Added -> ScanUiState.Added(
                    isbn13 = result.book.isbn13.orEmpty(),
                    title = result.book.title,
                )

                is AddBookResult.Duplicate -> ScanUiState.Duplicate(
                    isbn13 = result.book.isbn13.orEmpty(),
                    title = result.book.title,
                    copyCount = result.copyCount,
                )

                is AddBookResult.InvalidIsbn -> ScanUiState.Error(ScanFailure.INVALID_ISBN)

                is AddBookResult.NotFound -> ScanUiState.Error(
                    failure = ScanFailure.NOT_FOUND,
                    isbn13 = result.isbn13,
                )

                is AddBookResult.Failure -> ScanUiState.Error(
                    failure = result.reason.toScanFailure(),
                    isbn13 = result.isbn13,
                    retryIsbn = result.isbn13.takeIf { result.reason.retryable },
                )
            }
        }
    }

    fun addManualBook(draft: ManualBookDraft) {
        if (_manualRegistrationState.value === ManualRegistrationUiState.Saving) return
        _manualRegistrationState.value = ManualRegistrationUiState.Saving
        viewModelScope.launch {
            _manualRegistrationState.value = when (val result = repository.addManualBook(draft)) {
                is ManualBookResult.Added -> ManualRegistrationUiState.Added(result.book.title)
                is ManualBookResult.Duplicate -> ManualRegistrationUiState.Duplicate(
                    isbn13 = result.isbn13,
                    title = result.title,
                    copyCount = result.copyCount,
                )
                is ManualBookResult.Invalid -> ManualRegistrationUiState.Invalid(result.errors)
                ManualBookResult.Failure -> ManualRegistrationUiState.Error
            }
        }
    }

    fun clearManualRegistrationState() {
        if (_manualRegistrationState.value !== ManualRegistrationUiState.Saving) {
            _manualRegistrationState.value = ManualRegistrationUiState.Idle
        }
    }

    fun previewManualReconciliation(copyId: String, isbn: String) {
        if (_manualReconciliationState.value.isBusy) return
        _manualReconciliationState.value = ManualReconciliationUiState.Loading
        viewModelScope.launch {
            _manualReconciliationState.value = when (
                val result = repository.previewManualReconciliation(copyId, isbn)
            ) {
                is ManualReconciliationLookupResult.Ready ->
                    ManualReconciliationUiState.Preview(result.preview)
                is ManualReconciliationLookupResult.InvalidIsbn ->
                    ManualReconciliationUiState.Error(ReconciliationFailure.INVALID_ISBN)
                is ManualReconciliationLookupResult.NotFound ->
                    ManualReconciliationUiState.Error(ReconciliationFailure.NOT_FOUND)
                is ManualReconciliationLookupResult.Failure ->
                    ManualReconciliationUiState.Error(ReconciliationFailure.LOOKUP)
                ManualReconciliationLookupResult.NotManual ->
                    ManualReconciliationUiState.Error(ReconciliationFailure.CONFLICT)
            }
        }
    }

    fun confirmManualReconciliation() {
        val preview = (_manualReconciliationState.value as? ManualReconciliationUiState.Preview)
            ?.preview ?: return
        _manualReconciliationState.value = ManualReconciliationUiState.Applying(preview)
        viewModelScope.launch {
            _manualReconciliationState.value = when (
                repository.confirmManualReconciliation(preview)
            ) {
                ManualReconciliationApplyResult.Applied -> ManualReconciliationUiState.Applied
                ManualReconciliationApplyResult.Conflict ->
                    ManualReconciliationUiState.Error(ReconciliationFailure.CONFLICT)
                ManualReconciliationApplyResult.Failure ->
                    ManualReconciliationUiState.Error(ReconciliationFailure.SAVE)
            }
        }
    }

    fun clearManualReconciliationState() {
        if (!_manualReconciliationState.value.isBusy) {
            _manualReconciliationState.value = ManualReconciliationUiState.Idle
        }
    }

    fun retryScan() {
        val isbn = (_scanState.value as? ScanUiState.Error)?.retryIsbn ?: return
        lastSubmission = null
        submitIsbn(isbn)
    }

    fun startScanSession() {
        if (_scanSessionState.value is ScanSessionUiState.Working) return
        viewModelScope.launch {
            _scanSessionState.value = ScanSessionUiState.Working
            activeScanSessionId = repository.startScanSession()
            _scanSessionState.value =
                if (activeScanSessionId != null) ScanSessionUiState.Idle else ScanSessionUiState.Error
        }
    }

    fun finishScanSession(sessionId: String) {
        if (_scanSessionState.value is ScanSessionUiState.Working) return
        viewModelScope.launch {
            _scanSessionState.value = ScanSessionUiState.Working
            _scanSessionState.value = if (repository.finishScanSession(sessionId)) {
                activeScanSessionId = null
                ScanSessionUiState.Idle
            } else {
                ScanSessionUiState.Error
            }
        }
    }

    fun undoScanAttempt(attemptId: String) = undoScan { repository.undoScanAttempt(attemptId) }

    fun undoScanSession(sessionId: String) = undoScan { repository.undoScanSession(sessionId) }

    fun clearScanSessionState() {
        if (_scanSessionState.value !is ScanSessionUiState.Working) {
            _scanSessionState.value = ScanSessionUiState.Idle
        }
    }

    private fun undoScan(operation: suspend () -> ScanUndoResult) {
        if (_scanSessionState.value is ScanSessionUiState.Working) return
        viewModelScope.launch {
            _scanSessionState.value = ScanSessionUiState.Working
            _scanSessionState.value = when (val result = operation()) {
                is ScanUndoResult.Undone -> ScanSessionUiState.Undone(result.count)
                ScanUndoResult.Conflict -> ScanSessionUiState.Conflict
                ScanUndoResult.NotFound -> ScanSessionUiState.NotFound
                ScanUndoResult.Failure -> ScanSessionUiState.Error
            }
        }
    }

    private suspend fun recordCurrentScanAttempt(rawIsbn: String, result: AddBookResult) {
        val sessionId = activeScanSessionId
            ?: scanSessions.value.firstOrNull(ScanSession::isActive)?.id
            ?: repository.activeScanSessionId()
            ?: return
        activeScanSessionId = sessionId
        if (!repository.recordScanAttempt(sessionId, rawIsbn, result)) {
            _scanSessionState.value = ScanSessionUiState.Error
        }
    }

    fun lookupBookstore(rawIsbn: String) {
        if (_bookstoreState.value is BookstoreUiState.Loading ||
            _bookstoreState.value is BookstoreUiState.Updating
        ) return
        val now = System.currentTimeMillis()
        val previous = lastBookstoreSubmission
        if (previous != null && previous.first == rawIsbn &&
            now - previous.second < RESCAN_GUARD_MILLIS
        ) return
        lastBookstoreSubmission = rawIsbn to now
        viewModelScope.launch {
            _bookstoreState.value = BookstoreUiState.Loading(rawIsbn)
            _bookstoreState.value = when (val result = repository.lookupBookstore(rawIsbn)) {
                is BookstoreLookupResult.Found -> BookstoreUiState.Result(result.book)
                is BookstoreLookupResult.InvalidIsbn -> BookstoreUiState.Error(
                    ScanFailure.INVALID_ISBN,
                )
                is BookstoreLookupResult.NotFound -> BookstoreUiState.Error(
                    ScanFailure.NOT_FOUND,
                    isbn13 = result.isbn13,
                )
                is BookstoreLookupResult.Failure -> BookstoreUiState.Error(
                    failure = result.reason.toScanFailure(),
                    isbn13 = result.isbn13,
                    retryIsbn = result.isbn13.takeIf { result.reason.retryable },
                )
            }
        }
    }

    fun retryBookstoreLookup() {
        val isbn = (_bookstoreState.value as? BookstoreUiState.Error)?.retryIsbn ?: return
        lastBookstoreSubmission = null
        lookupBookstore(isbn)
    }

    fun changePurchaseState(transition: PurchaseTransition) {
        val book = when (val state = _bookstoreState.value) {
            is BookstoreUiState.Result -> state.book
            else -> return
        }
        _bookstoreState.value = BookstoreUiState.Updating(book, transition)
        viewModelScope.launch {
            _bookstoreState.value = when (
                val result = repository.changePurchaseState(book, transition)
            ) {
                is BookstoreChangeResult.Updated -> BookstoreUiState.Result(result.book)
                BookstoreChangeResult.Conflict -> BookstoreUiState.Error(
                    ScanFailure.SAVE,
                    isbn13 = book.isbn13,
                    retryIsbn = book.isbn13,
                )
                BookstoreChangeResult.Failure -> BookstoreUiState.Error(
                    ScanFailure.SAVE,
                    isbn13 = book.isbn13,
                    retryIsbn = book.isbn13,
                )
            }
        }
    }

    fun selectWishlistItem(book: BookstoreBook) {
        if (_bookstoreState.value !is BookstoreUiState.Updating) {
            _bookstoreState.value = BookstoreUiState.Result(book)
        }
    }

    fun clearBookstoreState() {
        if (_bookstoreState.value !is BookstoreUiState.Updating) {
            _bookstoreState.value = BookstoreUiState.Idle
        }
    }

    fun addDuplicateCopy(copyLabel: String) {
        val duplicate = _scanState.value as? ScanUiState.Duplicate ?: return
        _scanState.value = ScanUiState.Loading(duplicate.isbn13)
        viewModelScope.launch {
            val result = repository.addAnotherCopy(duplicate.isbn13, copyLabel)
            recordCurrentScanAttempt(duplicate.isbn13, result)
            _scanState.value = when (result) {
                is AddBookResult.Added -> ScanUiState.Added(
                    isbn13 = result.book.isbn13.orEmpty(),
                    title = result.book.title,
                )
                is AddBookResult.Duplicate -> ScanUiState.Duplicate(
                    result.book.isbn13.orEmpty(),
                    result.book.title,
                    result.copyCount,
                )
                is AddBookResult.InvalidIsbn -> ScanUiState.Error(ScanFailure.INVALID_ISBN)
                is AddBookResult.NotFound -> ScanUiState.Error(
                    ScanFailure.NOT_FOUND,
                    result.isbn13,
                )
                is AddBookResult.Failure -> ScanUiState.Error(
                    failure = result.reason.toScanFailure(),
                    isbn13 = result.isbn13,
                )
            }
        }
    }

    fun reportCameraError(message: String) {
        if (_scanState.value !is ScanUiState.Loading) {
            _scanState.value = ScanUiState.Error(
                failure = ScanFailure.CAMERA,
                message = message,
            )
        }
    }

    fun reportBookstoreCameraError(message: String) {
        if (_bookstoreState.value !is BookstoreUiState.Loading &&
            _bookstoreState.value !is BookstoreUiState.Updating
        ) {
            _bookstoreState.value = BookstoreUiState.Error(
                failure = ScanFailure.CAMERA,
                message = message,
            )
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

    fun addLocation(level: LocationLevel, parentId: String?, name: String) {
        mutateLocation {
            when (level) {
                LocationLevel.ROOM -> addRoom(name)
                LocationLevel.SHELF -> parentId?.let { addShelf(it, name) }
                    ?: LocationMutationResult.NotFound
                LocationLevel.TIER -> parentId?.let { addTier(it, name) }
                    ?: LocationMutationResult.NotFound
            }
        }
    }

    fun renameLocation(level: LocationLevel, id: String, name: String) {
        mutateLocation { rename(level, id, name) }
    }

    fun moveLocation(level: LocationLevel, id: String, direction: MoveDirection) {
        mutateLocation { move(level, id, direction) }
    }

    fun deleteLocation(
        level: LocationLevel,
        id: String,
        replacementTierId: String? = null,
        confirmUnset: Boolean = false,
    ) {
        mutateLocation(level, id) { delete(level, id, replacementTierId, confirmUnset) }
    }

    fun clearLocationMutationState() {
        _locationMutationState.value = LocationMutationUiState.Idle
    }

    fun moveBookWithinTier(copyId: String, direction: ShelfMoveDirection) {
        if (_shelfMoveState.value is ShelfMoveUiState.Moving) return
        _shelfMoveState.value = ShelfMoveUiState.Moving(copyId)
        viewModelScope.launch {
            _shelfMoveState.value = when (repository.moveBookWithinTier(copyId, direction)) {
                ShelfMoveResult.Moved -> ShelfMoveUiState.Moved
                ShelfMoveResult.Boundary -> ShelfMoveUiState.Boundary
                ShelfMoveResult.NotFound -> ShelfMoveUiState.Error("棚内の本が見つかりません")
                ShelfMoveResult.Failure -> ShelfMoveUiState.Error("棚内の順序を変更できませんでした")
            }
        }
    }

    fun clearShelfMoveState() {
        _shelfMoveState.value = ShelfMoveUiState.Idle
    }

    private fun mutateLocation(
        level: LocationLevel? = null,
        id: String? = null,
        operation: suspend LocationRepository.() -> LocationMutationResult,
    ) {
        if (_locationMutationState.value === LocationMutationUiState.Working) return
        val locations = locationRepository ?: return
        viewModelScope.launch {
            _locationMutationState.value = LocationMutationUiState.Working
            _locationMutationState.value = when (val result = locations.operation()) {
                LocationMutationResult.Success -> LocationMutationUiState.Success
                is LocationMutationResult.InUse -> LocationMutationUiState.InUse(
                    requireNotNull(level), requireNotNull(id), result.copyCount,
                )
                is LocationMutationResult.InvalidName -> LocationMutationUiState.Error(result.reason)
                LocationMutationResult.DuplicateName -> LocationMutationUiState.Error("同じ階層に同名の場所があります")
                LocationMutationResult.NotFound -> LocationMutationUiState.Error("場所が見つかりません")
                LocationMutationResult.InvalidDestination -> LocationMutationUiState.Error("移動先の段を選び直してください")
                LocationMutationResult.Failure -> LocationMutationUiState.Error("場所を更新できませんでした")
            }
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
        private const val SEARCH_DEBOUNCE_MILLIS = 250L

        fun factory(
            repository: LibraryRepository,
            databaseBackupManager: DatabaseBackupManager,
            locationRepository: LocationRepository,
            librarySearchSettings: LibrarySearchSettingsStore = InMemoryLibrarySearchSettingsStore,
            seriesRepository: SeriesRepository? = null,
            workGroupRepository: WorkGroupRepository? = null,
            seriesWatchRepository: SeriesWatchRepository? = null,
            seriesWatchScheduler: SeriesWatchScheduler? = null,
            syncStatusRepository: SyncStatusRepository? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java))
                    return MainViewModel(
                        repository = repository,
                        databaseBackupManager = databaseBackupManager,
                        locationRepository = locationRepository,
                        librarySearchSettings = librarySearchSettings,
                        seriesRepository = seriesRepository,
                        workGroupRepository = workGroupRepository,
                        seriesWatchRepository = seriesWatchRepository,
                        seriesWatchScheduler = seriesWatchScheduler,
                        syncStatusRepository = syncStatusRepository,
                    ) as T
                }
            }
    }
}

sealed interface SeriesWatchMutationUiState {
    data object Idle : SeriesWatchMutationUiState
    data object Working : SeriesWatchMutationUiState
    data object Updated : SeriesWatchMutationUiState
    data object PermissionDenied : SeriesWatchMutationUiState
    data object Invalid : SeriesWatchMutationUiState
    data object Error : SeriesWatchMutationUiState
}

data class LibrarySearchResult(
    val criteria: LibrarySearchCriteria,
    val books: List<LibraryBook>,
)

sealed interface SeriesEditorUiState {
    data object Idle : SeriesEditorUiState
    data object Loading : SeriesEditorUiState
    data class Ready(val suggestion: SeriesSuggestion) : SeriesEditorUiState
    data object Saving : SeriesEditorUiState
    data class Saved(val seriesId: String, val membershipCount: Int) : SeriesEditorUiState
    data object Removed : SeriesEditorUiState
    data object Conflict : SeriesEditorUiState
    data object Invalid : SeriesEditorUiState
    data object Error : SeriesEditorUiState
}

sealed interface WorkVariantUiState {
    data object Idle : WorkVariantUiState
    data object Loading : WorkVariantUiState
    data class Ready(val editor: WorkVariantEditor) : WorkVariantUiState
    data object Saving : WorkVariantUiState
    data object Conflict : WorkVariantUiState
    data object Invalid : WorkVariantUiState
    data object Error : WorkVariantUiState
}

sealed interface LocationMutationUiState {
    data object Idle : LocationMutationUiState
    data object Working : LocationMutationUiState
    data object Success : LocationMutationUiState
    data class InUse(val level: LocationLevel, val id: String, val copyCount: Int) : LocationMutationUiState
    data class Error(val message: String) : LocationMutationUiState
}

sealed interface ShelfMoveUiState {
    data object Idle : ShelfMoveUiState
    data class Moving(val copyId: String) : ShelfMoveUiState
    data object Moved : ShelfMoveUiState
    data object Boundary : ShelfMoveUiState
    data class Error(val message: String) : ShelfMoveUiState
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
        val copyCount: Int = 1,
    ) : ScanUiState

    data class Error(
        val failure: ScanFailure,
        val isbn13: String? = null,
        val retryIsbn: String? = null,
        val message: String? = null,
    ) : ScanUiState
}

sealed interface ScanSessionUiState {
    data object Idle : ScanSessionUiState
    data object Working : ScanSessionUiState
    data class Undone(val count: Int) : ScanSessionUiState
    data object Conflict : ScanSessionUiState
    data object NotFound : ScanSessionUiState
    data object Error : ScanSessionUiState
}

sealed interface ManualRegistrationUiState {
    data object Idle : ManualRegistrationUiState
    data object Saving : ManualRegistrationUiState
    data class Added(val title: String) : ManualRegistrationUiState
    data class Duplicate(
        val isbn13: String,
        val title: String,
        val copyCount: Int,
    ) : ManualRegistrationUiState
    data class Invalid(val errors: List<ManualBookValidationError>) : ManualRegistrationUiState
    data object Error : ManualRegistrationUiState
}

sealed interface ManualReconciliationUiState {
    data object Idle : ManualReconciliationUiState
    data object Loading : ManualReconciliationUiState
    data class Preview(val preview: ManualReconciliationPreview) : ManualReconciliationUiState
    data class Applying(val preview: ManualReconciliationPreview) : ManualReconciliationUiState
    data object Applied : ManualReconciliationUiState
    data class Error(val failure: ReconciliationFailure) : ManualReconciliationUiState
}

enum class ReconciliationFailure { INVALID_ISBN, NOT_FOUND, LOOKUP, CONFLICT, SAVE }

private val ManualReconciliationUiState.isBusy: Boolean
    get() = this === ManualReconciliationUiState.Loading ||
        this is ManualReconciliationUiState.Applying

sealed interface BookstoreUiState {
    data object Idle : BookstoreUiState
    data class Loading(val isbn: String) : BookstoreUiState
    data class Result(val book: BookstoreBook) : BookstoreUiState
    data class Updating(
        val book: BookstoreBook,
        val transition: PurchaseTransition,
    ) : BookstoreUiState
    data class Error(
        val failure: ScanFailure,
        val isbn13: String? = null,
        val retryIsbn: String? = null,
        val message: String? = null,
    ) : BookstoreUiState
}

enum class ScanFailure {
    INVALID_ISBN,
    NOT_FOUND,
    OFFLINE,
    TIMEOUT,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    NETWORK,
    REQUEST_REJECTED,
    INVALID_RESPONSE,
    SAVE,
    CAMERA,
}

private fun AddBookFailure.toScanFailure(): ScanFailure = when (this) {
    AddBookFailure.OFFLINE -> ScanFailure.OFFLINE
    AddBookFailure.TIMEOUT -> ScanFailure.TIMEOUT
    AddBookFailure.RATE_LIMITED -> ScanFailure.RATE_LIMITED
    AddBookFailure.SERVICE_UNAVAILABLE -> ScanFailure.SERVICE_UNAVAILABLE
    AddBookFailure.NETWORK -> ScanFailure.NETWORK
    AddBookFailure.REQUEST_REJECTED -> ScanFailure.REQUEST_REJECTED
    AddBookFailure.INVALID_RESPONSE -> ScanFailure.INVALID_RESPONSE
    AddBookFailure.SAVE -> ScanFailure.SAVE
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
