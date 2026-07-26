package dev.ndcshelf.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Storage
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ndcshelf.app.BookDeleteFailure
import dev.ndcshelf.app.BookDeleteUiState
import dev.ndcshelf.app.BookEditFailure
import dev.ndcshelf.app.BookEditUiState
import dev.ndcshelf.app.DatabaseBackupUiState
import dev.ndcshelf.app.LibraryImportUiState
import dev.ndcshelf.app.LibraryExportUiState
import dev.ndcshelf.app.MainActivity
import dev.ndcshelf.app.MainViewModel
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.export.LibraryExportFormat
import dev.ndcshelf.app.ui.screens.InsightsScreen
import dev.ndcshelf.app.ui.screens.LibraryScreen
import dev.ndcshelf.app.ui.screens.ScanScreen
import dev.ndcshelf.app.ui.screens.DataManagementScreen
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NdcShelfApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val books by viewModel.books.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val bookEditState by viewModel.bookEditState.collectAsStateWithLifecycle()
    val bookDeleteState by viewModel.bookDeleteState.collectAsStateWithLifecycle()
    val databaseBackupState by viewModel.databaseBackupState.collectAsStateWithLifecycle()
    val libraryExportState by viewModel.libraryExportState.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(AppDestination.LIBRARY) }

    fun saveExport(uri: Uri?, format: LibraryExportFormat) {
        if (uri == null) {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.data_operation_cancelled))
            }
            return
        }
        val output = try {
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

    val jsonExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LibraryExportFormat.JSON.mimeType),
    ) { uri -> saveExport(uri, LibraryExportFormat.JSON) }
    val csvExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LibraryExportFormat.CSV.mimeType),
    ) { uri -> saveExport(uri, LibraryExportFormat.CSV) }
    val jsonImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.data_operation_cancelled))
            }
        } else {
            val input = try {
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
    val csvImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.data_operation_cancelled))
            }
        } else {
            val input = try {
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
    val databaseBackupCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.data_operation_cancelled))
            }
        } else {
            val output = try {
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
    val databaseBackupRestorer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.data_operation_cancelled))
            }
        } else {
            val input = try {
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
        val message = when (val state = libraryExportState) {
            is LibraryExportUiState.Success -> resources.getString(
                R.string.export_success,
                state.bookCount,
            )
            LibraryExportUiState.Error -> resources.getString(R.string.export_failure)
            else -> return@LaunchedEffect
        }
        snackbarHostState.showSnackbar(message)
        viewModel.consumeLibraryExportResult()
    }

    LaunchedEffect(bookEditState) {
        when (val state = bookEditState) {
            is BookEditUiState.Saved -> {
                val result = snackbarHostState.showSnackbar(
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
                val message = when (state.failure) {
                    BookEditFailure.NOT_FOUND -> R.string.book_edit_not_found
                    BookEditFailure.SAVE -> R.string.book_edit_failure
                    BookEditFailure.UNDO -> R.string.book_edit_undo_failure
                }
                snackbarHostState.showSnackbar(resources.getString(message))
                viewModel.clearBookEditState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(bookDeleteState) {
        when (val state = bookDeleteState) {
            is BookDeleteUiState.Deleted -> {
                val result = snackbarHostState.showSnackbar(
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
                val message = when (state.failure) {
                    BookDeleteFailure.NOT_FOUND -> R.string.book_delete_not_found
                    BookDeleteFailure.DELETE -> R.string.book_delete_failure
                    BookDeleteFailure.RESTORE_CONFLICT -> R.string.book_delete_restore_conflict
                    BookDeleteFailure.RESTORE -> R.string.book_delete_restore_failure
                }
                snackbarHostState.showSnackbar(resources.getString(message))
                viewModel.clearBookDeleteState()
            }
            else -> Unit
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
                val restart = Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                )
                context.startActivity(restart)
                (context as? Activity)?.finish()
            }
            else -> Unit
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(tonalElevation = 2.dp) {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = selected == destination,
                        onClick = { selected = destination },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        when (selected) {
            AppDestination.LIBRARY -> LibraryScreen(
                books = books,
                onSaveBook = viewModel::saveBookEdit,
                onDeleteBook = viewModel::deleteBook,
                bookEditState = bookEditState,
                onClearBookEditState = viewModel::clearBookEditState,
                bookDeleteState = bookDeleteState,
                onClearBookDeleteState = viewModel::clearBookDeleteState,
                contentPadding = contentPadding,
            )

            AppDestination.SCAN -> ScanScreen(
                scanState = scanState,
                onSubmitIsbn = viewModel::submitIsbn,
                onCameraError = viewModel::reportCameraError,
                onClearState = viewModel::clearScanState,
                contentPadding = contentPadding,
            )

            AppDestination.INSIGHTS -> InsightsScreen(
                books = books,
                contentPadding = contentPadding,
            )

            AppDestination.DATA -> DataManagementScreen(
                bookCount = books.size,
                exportInProgress = libraryExportState === LibraryExportUiState.Exporting,
                importState = importState,
                databaseBackupState = databaseBackupState,
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
                contentPadding = contentPadding,
            )
        }
    }
}

private enum class AppDestination(
    val label: String,
    val icon: ImageVector,
) {
    LIBRARY("本棚", Icons.AutoMirrored.Rounded.LibraryBooks),
    SCAN("スキャン", Icons.Rounded.QrCodeScanner),
    INSIGHTS("分類", Icons.Rounded.Analytics),
    DATA("データ", Icons.Rounded.Storage),
}
