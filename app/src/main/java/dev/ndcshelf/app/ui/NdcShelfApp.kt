package dev.ndcshelf.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.ndcshelf.app.BookDeleteFailure
import dev.ndcshelf.app.BookDeleteUiState
import dev.ndcshelf.app.BookEditFailure
import dev.ndcshelf.app.BookEditUiState
import dev.ndcshelf.app.BuildConfig
import dev.ndcshelf.app.DatabaseBackupUiState
import dev.ndcshelf.app.LibraryExportUiState
import dev.ndcshelf.app.LibraryImportUiState
import dev.ndcshelf.app.MainActivity
import dev.ndcshelf.app.MainViewModel
import dev.ndcshelf.app.NdcShelfApplication
import dev.ndcshelf.app.R
import dev.ndcshelf.app.SeriesEditorUiState
import dev.ndcshelf.app.SeriesWatchMutationUiState
import dev.ndcshelf.app.WorkVariantViewModel
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.export.LibraryExportFormat
import dev.ndcshelf.app.ui.navigation.ConsentRoute
import dev.ndcshelf.app.ui.navigation.DataGraph
import dev.ndcshelf.app.ui.navigation.DataRoute
import dev.ndcshelf.app.ui.navigation.InfoRoute
import dev.ndcshelf.app.ui.navigation.InsightsRoute
import dev.ndcshelf.app.ui.navigation.LibraryGraph
import dev.ndcshelf.app.ui.navigation.LibraryRoute
import dev.ndcshelf.app.ui.navigation.ScanRoute
import dev.ndcshelf.app.ui.navigation.SeriesGraph
import dev.ndcshelf.app.ui.navigation.SeriesRoute
import dev.ndcshelf.app.ui.navigation.SeriesSuggestionRoute
import dev.ndcshelf.app.ui.navigation.TopLevelDestination
import dev.ndcshelf.app.ui.navigation.WorkVariantRoute
import dev.ndcshelf.app.ui.screens.AppInfoScreen
import dev.ndcshelf.app.ui.screens.ConsentPayloadDialog
import dev.ndcshelf.app.ui.screens.ConsentScreen
import dev.ndcshelf.app.ui.screens.DataManagementScreen
import dev.ndcshelf.app.ui.screens.InsightsScreen
import dev.ndcshelf.app.ui.screens.LibraryScreen
import dev.ndcshelf.app.ui.screens.ScanScreen
import dev.ndcshelf.app.ui.screens.SeriesScreen
import dev.ndcshelf.app.ui.screens.SeriesSuggestionScreen
import dev.ndcshelf.app.ui.screens.WorkVariantScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NdcShelfApp(
    viewModel: MainViewModel,
    requestedEditionId: String? = null,
    onBookDetailRequestHandled: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val scanSessions by viewModel.scanSessions.collectAsStateWithLifecycle()
    val scanSessionState by viewModel.scanSessionState.collectAsStateWithLifecycle()
    val manualRegistrationState by viewModel.manualRegistrationState.collectAsStateWithLifecycle()
    val manualReconciliationState by viewModel.manualReconciliationState.collectAsStateWithLifecycle()
    val bookstoreState by viewModel.bookstoreState.collectAsStateWithLifecycle()
    val wishlist by viewModel.wishlist.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val bookEditState by viewModel.bookEditState.collectAsStateWithLifecycle()
    val bookDeleteState by viewModel.bookDeleteState.collectAsStateWithLifecycle()
    val databaseBackupState by viewModel.databaseBackupState.collectAsStateWithLifecycle()
    val libraryExportState by viewModel.libraryExportState.collectAsStateWithLifecycle()
    val locations by viewModel.locations.collectAsStateWithLifecycle()
    val locationMutationState by viewModel.locationMutationState.collectAsStateWithLifecycle()
    val shelfMoveState by viewModel.shelfMoveState.collectAsStateWithLifecycle()
    val libraryStats by viewModel.libraryStats.collectAsStateWithLifecycle()
    val seriesEditorState by viewModel.seriesEditorState.collectAsStateWithLifecycle()
    val seriesWatchMutationState by viewModel.seriesWatchMutationState.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    var selectedSeriesId by rememberSaveable { mutableStateOf<String?>(null) }
    var bookstoreRequestKey by rememberSaveable { mutableIntStateOf(0) }
    var pendingSeriesWatchId by rememberSaveable { mutableStateOf<String?>(null) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    fun navigateToTab(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            pendingSeriesWatchId?.let { seriesId ->
                if (granted) {
                    viewModel.setSeriesWatchEnabled(seriesId, true)
                } else {
                    viewModel.reportSeriesWatchPermissionDenied()
                }
            }
            pendingSeriesWatchId = null
        }

    fun setSeriesWatch(
        seriesId: String,
        enabled: Boolean,
    ) {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.setSeriesWatchEnabled(seriesId, enabled)
        } else {
            pendingSeriesWatchId = seriesId
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val seriesWatchConsentRequest by viewModel.seriesWatchConsentRequest.collectAsStateWithLifecycle()
    seriesWatchConsentRequest?.let { pendingSeriesId ->
        val seriesCatalogForConsent by viewModel.seriesCatalog.collectAsStateWithLifecycle()
        val seriesWatchesForConsent by viewModel.seriesWatches.collectAsStateWithLifecycle()
        val pendingTitle =
            seriesCatalogForConsent
                .firstOrNull { it.series.id == pendingSeriesId }
                ?.series
                ?.name
        val payloadItems =
            buildList {
                pendingTitle?.let(::add)
                seriesWatchesForConsent
                    .filter { it.watch.enabled && it.watch.seriesId != pendingSeriesId }
                    .forEach { add(it.watch.queryTitle) }
            }
        ConsentPayloadDialog(
            purpose = ConsentPurpose.SERIES_RELEASE_WATCH,
            payloadItems = payloadItems,
            onAccept = viewModel::grantSeriesWatchConsent,
            onDismiss = viewModel::declineSeriesWatchConsent,
        )
    }

    LaunchedEffect(seriesEditorState) {
        val onSuggestionScreen =
            navController.currentBackStackEntry?.destination?.hasRoute<SeriesSuggestionRoute>() == true
        if (seriesEditorState === SeriesEditorUiState.Removed) {
            snackbarHostState.showSnackbar(resources.getString(R.string.series_membership_removed))
            viewModel.clearSeriesEditorState()
        } else if (seriesEditorState === SeriesEditorUiState.Error && !onSuggestionScreen) {
            snackbarHostState.showSnackbar(resources.getString(R.string.series_membership_remove_error))
            viewModel.clearSeriesEditorState()
        }
    }

    LaunchedEffect(seriesWatchMutationState) {
        val message =
            when (seriesWatchMutationState) {
                SeriesWatchMutationUiState.PermissionDenied -> {
                    R.string.series_watch_permission_denied
                }

                SeriesWatchMutationUiState.Invalid, SeriesWatchMutationUiState.Error -> {
                    R.string.series_watch_update_error
                }

                SeriesWatchMutationUiState.Updated -> {
                    R.string.series_watch_updated
                }

                else -> {
                    null
                }
            }
        if (message != null) {
            snackbarHostState.showSnackbar(resources.getString(message))
            viewModel.clearSeriesWatchMutationState()
        }
    }

    fun saveExport(
        uri: Uri?,
        format: LibraryExportFormat,
    ) {
        if (uri == null) {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.data_operation_cancelled))
            }
            return
        }
        val output =
            try {
                context.contentResolver.openOutputStream(uri, "wt")
            } catch (_: Exception) {
                null
            }
        if (output == null) {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.export_failure))
            }
        } else {
            viewModel.exportLibrary(format, output)
        }
    }

    val jsonExporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(LibraryExportFormat.JSON.mimeType),
        ) { uri -> saveExport(uri, LibraryExportFormat.JSON) }
    val csvExporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(LibraryExportFormat.CSV.mimeType),
        ) { uri -> saveExport(uri, LibraryExportFormat.CSV) }
    val jsonImporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) {
                scope.launch {
                    snackbarHostState.showSnackbar(resources.getString(R.string.data_operation_cancelled))
                }
            } else {
                val input =
                    try {
                        context.contentResolver.openInputStream(uri)
                    } catch (_: Exception) {
                        null
                    }
                if (input == null) {
                    scope.launch {
                        snackbarHostState.showSnackbar(resources.getString(R.string.import_open_failure))
                    }
                } else {
                    viewModel.loadJsonImport(input)
                }
            }
        }
    val csvImporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) {
                scope.launch {
                    snackbarHostState.showSnackbar(resources.getString(R.string.data_operation_cancelled))
                }
            } else {
                val input =
                    try {
                        context.contentResolver.openInputStream(uri)
                    } catch (_: Exception) {
                        null
                    }
                if (input == null) {
                    scope.launch {
                        snackbarHostState.showSnackbar(resources.getString(R.string.import_open_failure))
                    }
                } else {
                    viewModel.loadCsvImport(input)
                }
            }
        }
    val databaseBackupCreator =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            if (uri == null) {
                scope.launch {
                    snackbarHostState.showSnackbar(resources.getString(R.string.data_operation_cancelled))
                }
            } else {
                val output =
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")
                    } catch (_: Exception) {
                        null
                    }
                if (output == null) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            resources.getString(R.string.database_backup_output_failure),
                        )
                    }
                } else {
                    viewModel.createDatabaseBackup(output)
                }
            }
        }
    val databaseBackupRestorer =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) {
                scope.launch {
                    snackbarHostState.showSnackbar(resources.getString(R.string.data_operation_cancelled))
                }
            } else {
                val input =
                    try {
                        context.contentResolver.openInputStream(uri)
                    } catch (_: Exception) {
                        null
                    }
                if (input == null) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            resources.getString(R.string.database_backup_open_failure),
                        )
                    }
                } else {
                    viewModel.loadDatabaseBackup(input)
                }
            }
        }

    LaunchedEffect(importState) {
        val result = importState as? LibraryImportUiState.Success ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            resources.getString(
                R.string.import_success,
                result.addedCount,
                result.updatedCount,
                result.skippedCount,
            ),
        )
        viewModel.consumeImportSuccess()
    }

    LaunchedEffect(libraryExportState) {
        val message =
            when (val state = libraryExportState) {
                is LibraryExportUiState.Success -> {
                    resources.getString(
                        R.string.export_success,
                        state.bookCount,
                    )
                }

                LibraryExportUiState.Error -> {
                    resources.getString(R.string.export_failure)
                }

                else -> {
                    return@LaunchedEffect
                }
            }
        snackbarHostState.showSnackbar(message)
        viewModel.consumeLibraryExportResult()
    }

    LaunchedEffect(bookEditState) {
        when (val state = bookEditState) {
            is BookEditUiState.Saved -> {
                val result =
                    snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.book_edit_success),
                        actionLabel = resources.getString(R.string.book_edit_undo),
                        withDismissAction = true,
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.undoLastBookEdit()
                } else {
                    viewModel.clearBookEditState()
                }
            }

            BookEditUiState.Undone -> {
                snackbarHostState.showSnackbar(resources.getString(R.string.book_edit_undone))
                viewModel.clearBookEditState()
            }

            is BookEditUiState.Error -> {
                val message =
                    when (state.failure) {
                        BookEditFailure.NOT_FOUND -> R.string.book_edit_not_found
                        BookEditFailure.SAVE -> R.string.book_edit_failure
                        BookEditFailure.UNDO -> R.string.book_edit_undo_failure
                    }
                snackbarHostState.showSnackbar(resources.getString(message))
                viewModel.clearBookEditState()
            }

            else -> {
                Unit
            }
        }
    }

    LaunchedEffect(bookDeleteState) {
        when (val state = bookDeleteState) {
            is BookDeleteUiState.Deleted -> {
                val result =
                    snackbarHostState.showSnackbar(
                        message = resources.getString(R.string.book_delete_success),
                        actionLabel = resources.getString(R.string.book_delete_undo),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.undoLastBookDeletion()
                } else {
                    viewModel.clearBookDeleteState()
                }
            }

            BookDeleteUiState.Restored -> {
                snackbarHostState.showSnackbar(resources.getString(R.string.book_delete_restored))
                viewModel.clearBookDeleteState()
            }

            is BookDeleteUiState.Error -> {
                val message =
                    when (state.failure) {
                        BookDeleteFailure.NOT_FOUND -> R.string.book_delete_not_found
                        BookDeleteFailure.DELETE -> R.string.book_delete_failure
                        BookDeleteFailure.RESTORE_CONFLICT -> R.string.book_delete_restore_conflict
                        BookDeleteFailure.RESTORE -> R.string.book_delete_restore_failure
                    }
                snackbarHostState.showSnackbar(resources.getString(message))
                viewModel.clearBookDeleteState()
            }

            else -> {
                Unit
            }
        }
    }

    LaunchedEffect(databaseBackupState) {
        when (val state = databaseBackupState) {
            is DatabaseBackupUiState.Created -> {
                snackbarHostState.showSnackbar(
                    resources.getString(R.string.database_backup_created, state.copyCount),
                )
                viewModel.dismissDatabaseBackup()
            }

            is DatabaseBackupUiState.Restored -> {
                snackbarHostState.showSnackbar(
                    resources.getString(
                        R.string.database_restore_success,
                        state.restoredCopyCount,
                        state.automaticBackupName,
                    ),
                )
                val restart =
                    Intent(context, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                context.startActivity(restart)
                (context as? Activity)?.finish()
            }

            else -> {
                Unit
            }
        }
    }

    fun requestExport(format: LibraryExportFormat) {
        val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "ndc-shelf-$date.${format.extension}"
        when (format) {
            LibraryExportFormat.JSON -> jsonExporter.launch(fileName)
            LibraryExportFormat.CSV -> csvExporter.launch(fileName)
        }
    }

    fun requestDatabaseBackup() {
        val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        databaseBackupCreator.launch("ndc-shelf-database-$date.ndcshelfbackup")
    }

    fun openExternalUrl(url: String) {
        runCatching {
            val uri = url.toUri()
            require(uri.scheme == "https" || uri.scheme == "http")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.external_link_failure))
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(tonalElevation = 2.dp) {
                TopLevelDestination.entries.forEach { destination ->
                    val label = stringResource(destination.labelRes)
                    val isSelected =
                        currentDestination?.hierarchy?.any {
                            it.hasRoute(destination.route::class)
                        } == true
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { navigateToTab(destination.route) },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = label,
                            )
                        },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = LibraryGraph,
        ) {
            navigation<LibraryGraph>(startDestination = LibraryRoute) {
                composable<LibraryRoute> {
                    val criteria by viewModel.librarySearchCriteria.collectAsStateWithLifecycle()
                    val result by viewModel.librarySearchResult.collectAsStateWithLifecycle()
                    LibraryScreen(
                        books = result.books,
                        searchCriteria = criteria,
                        searchIsCurrent = result.criteria == criteria,
                        libraryStats = libraryStats,
                        onQueryChange = viewModel::updateLibraryQuery,
                        onReadingStatusChange = viewModel::updateLibraryReadingStatus,
                        onSortChange = viewModel::updateLibrarySort,
                        onSelectedEditionChange = viewModel::selectLibraryEdition,
                        initialEditionId = requestedEditionId,
                        onInitialEditionHandled = onBookDetailRequestHandled,
                        onSaveBook = viewModel::saveBookEdit,
                        onDeleteBook = viewModel::deleteBook,
                        bookEditState = bookEditState,
                        onClearBookEditState = viewModel::clearBookEditState,
                        bookDeleteState = bookDeleteState,
                        onClearBookDeleteState = viewModel::clearBookDeleteState,
                        locations = locations,
                        locationMutationState = locationMutationState,
                        onAddLocation = viewModel::addLocation,
                        onRenameLocation = viewModel::renameLocation,
                        onMoveLocation = viewModel::moveLocation,
                        onDeleteLocation = viewModel::deleteLocation,
                        onClearLocationState = viewModel::clearLocationMutationState,
                        shelfMoveState = shelfMoveState,
                        onMoveBookWithinTier = viewModel::moveBookWithinTier,
                        onClearShelfMoveState = viewModel::clearShelfMoveState,
                        manualReconciliationState = manualReconciliationState,
                        onPreviewManualReconciliation = viewModel::previewManualReconciliation,
                        onConfirmManualReconciliation = viewModel::confirmManualReconciliation,
                        onClearManualReconciliation = viewModel::clearManualReconciliationState,
                        onManageSeries = { workId ->
                            navigateToTab(SeriesGraph)
                            navController.navigate(SeriesSuggestionRoute(workId)) {
                                launchSingleTop = true
                            }
                        },
                        onManageVariants = { workId ->
                            navController.navigate(WorkVariantRoute(workId)) {
                                launchSingleTop = true
                            }
                        },
                        contentPadding = contentPadding,
                    )
                }

                composable<WorkVariantRoute> {
                    val application = context.applicationContext as NdcShelfApplication
                    val workVariantViewModel: WorkVariantViewModel =
                        viewModel(
                            factory =
                                WorkVariantViewModel.factory(
                                    application.container.workGroupRepository,
                                ),
                        )
                    val workVariantState by workVariantViewModel.state.collectAsStateWithLifecycle()
                    WorkVariantScreen(
                        state = workVariantState,
                        onBack = { navController.popBackStack() },
                        onLink = workVariantViewModel::linkVariant,
                        onUnlink = workVariantViewModel::unlinkVariant,
                        onSetSeriesSubstitution = workVariantViewModel::setSeriesSubstitution,
                        contentPadding = contentPadding,
                    )
                }
            }

            composable<ScanRoute> {
                ScanScreen(
                    scanState = scanState,
                    bookstoreState = bookstoreState,
                    wishlist = wishlist,
                    scanSessions = scanSessions,
                    scanSessionState = scanSessionState,
                    manualRegistrationState = manualRegistrationState,
                    onSubmitIsbn = viewModel::submitIsbn,
                    onLookupBookstore = viewModel::lookupBookstore,
                    onCameraError = viewModel::reportCameraError,
                    onBookstoreCameraError = viewModel::reportBookstoreCameraError,
                    onRetry = viewModel::retryScan,
                    onRetryBookstore = viewModel::retryBookstoreLookup,
                    onClearState = viewModel::clearScanState,
                    onClearBookstoreState = viewModel::clearBookstoreState,
                    onAddDuplicateCopy = viewModel::addDuplicateCopy,
                    onAddManualBook = viewModel::addManualBook,
                    onClearManualRegistrationState = viewModel::clearManualRegistrationState,
                    onChangePurchaseState = viewModel::changePurchaseState,
                    onSelectWishlistItem = viewModel::selectWishlistItem,
                    onStartScanSession = viewModel::startScanSession,
                    onFinishScanSession = viewModel::finishScanSession,
                    onUndoScanAttempt = viewModel::undoScanAttempt,
                    onUndoScanSession = viewModel::undoScanSession,
                    contentPadding = contentPadding,
                    bookstoreRequestKey = bookstoreRequestKey,
                )
            }

            navigation<SeriesGraph>(startDestination = SeriesRoute) {
                composable<SeriesRoute> {
                    val seriesCatalog by viewModel.seriesCatalog.collectAsStateWithLifecycle()
                    val seriesWatches by viewModel.seriesWatches.collectAsStateWithLifecycle()
                    SeriesScreen(
                        series = seriesCatalog,
                        watches = seriesWatches,
                        selectedSeriesId = selectedSeriesId,
                        onSelectSeries = { selectedSeriesId = it },
                        onOpenEdition = { editionId ->
                            viewModel.selectLibraryEdition(editionId)
                            navigateToTab(LibraryGraph)
                        },
                        onOpenBookstore = { isbn ->
                            bookstoreRequestKey += 1
                            viewModel.lookupBookstore(isbn)
                            navigateToTab(ScanRoute)
                        },
                        onManageSuggestions = {
                            viewModel.clearSeriesEditorState()
                            navController.navigate(SeriesSuggestionRoute()) {
                                launchSingleTop = true
                            }
                        },
                        onRemoveMembership = viewModel::removeSeriesMembership,
                        onSetWatchEnabled = ::setSeriesWatch,
                        contentPadding = contentPadding,
                    )
                }

                composable<SeriesSuggestionRoute> { entry ->
                    val route = entry.toRoute<SeriesSuggestionRoute>()
                    val seriesCatalog by viewModel.seriesCatalog.collectAsStateWithLifecycle()
                    val seriesSuggestions by viewModel.seriesSuggestions.collectAsStateWithLifecycle()
                    LaunchedEffect(route.workId) {
                        route.workId?.let(viewModel::prepareSeriesEditor)
                    }
                    SeriesSuggestionScreen(
                        suggestions = seriesSuggestions,
                        catalog = seriesCatalog,
                        focusedSuggestion = (seriesEditorState as? SeriesEditorUiState.Ready)?.suggestion,
                        state = seriesEditorState,
                        onConfirm = viewModel::confirmSeries,
                        onBack = {
                            viewModel.clearSeriesEditorState()
                            navController.popBackStack()
                        },
                        onSaved = { seriesId ->
                            selectedSeriesId = seriesId
                            navController.popBackStack()
                        },
                        onClearState = viewModel::clearSeriesEditorState,
                        contentPadding = contentPadding,
                    )
                }
            }

            composable<InsightsRoute> {
                val books by viewModel.books.collectAsStateWithLifecycle()
                InsightsScreen(books = books, contentPadding = contentPadding)
            }

            navigation<DataGraph>(startDestination = DataRoute) {
                composable<DataRoute> {
                    DataManagementScreen(
                        bookCount = libraryStats.totalCount,
                        exportInProgress = libraryExportState === LibraryExportUiState.Exporting,
                        importState = importState,
                        databaseBackupState = databaseBackupState,
                        syncStatus = syncStatus,
                        onExportJson = { requestExport(LibraryExportFormat.JSON) },
                        onExportCsv = { requestExport(LibraryExportFormat.CSV) },
                        onImportJson = {
                            jsonImporter.launch(arrayOf("application/json", "text/json"))
                        },
                        onImportCsv = {
                            csvImporter.launch(arrayOf("text/csv", "text/comma-separated-values"))
                        },
                        onCreateDatabaseBackup = ::requestDatabaseBackup,
                        onSelectDatabaseBackup = {
                            databaseBackupRestorer.launch(
                                arrayOf("application/zip", "application/octet-stream"),
                            )
                        },
                        onSelectImportPolicy = viewModel::selectImportConflictPolicy,
                        onConfirmImport = viewModel::confirmImport,
                        onDismissImport = viewModel::dismissImport,
                        onConfirmDatabaseRestore = viewModel::confirmDatabaseRestore,
                        onDismissDatabaseBackup = viewModel::dismissDatabaseBackup,
                        onOpenConsent = {
                            navController.navigate(ConsentRoute) { launchSingleTop = true }
                        },
                        contentPadding = contentPadding,
                    )
                }

                composable<ConsentRoute> {
                    val consents by viewModel.consents.collectAsStateWithLifecycle()
                    val seriesWatches by viewModel.seriesWatches.collectAsStateWithLifecycle()
                    ConsentScreen(
                        consents = consents,
                        payloadPreviewItems =
                            seriesWatches
                                .filter { it.watch.enabled }
                                .map { it.watch.queryTitle },
                        onGrant = viewModel::grantConsent,
                        onRevoke = viewModel::revokeConsent,
                        contentPadding = contentPadding,
                    )
                }
            }

            composable<InfoRoute> {
                AppInfoScreen(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    buildType = BuildConfig.BUILD_TYPE,
                    onOpenUrl = ::openExternalUrl,
                    contentPadding = contentPadding,
                )
            }
        }
    }

    LaunchedEffect(requestedEditionId) {
        if (requestedEditionId != null) {
            // NavHostがgraphを設定するまで待ってから遷移する
            navController.currentBackStackEntryFlow.first()
            navigateToTab(LibraryGraph)
            viewModel.selectLibraryEdition(requestedEditionId)
        }
    }
}
