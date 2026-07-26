package dev.ndcshelf.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.ndcshelf.app.MainViewModel
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.export.LibraryExportFormat
import dev.ndcshelf.app.domain.export.LibraryExporter
import dev.ndcshelf.app.ui.screens.InsightsScreen
import dev.ndcshelf.app.ui.screens.LibraryScreen
import dev.ndcshelf.app.ui.screens.ScanScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
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
    var selected by rememberSaveable { mutableStateOf(AppDestination.LIBRARY) }

    fun saveExport(uri: Uri?, format: LibraryExportFormat) {
        if (uri == null) return
        val booksToExport = books.toList()
        scope.launch {
            val succeeded = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        LibraryExporter.write(booksToExport, format, output)
                    } ?: throw IOException("保存先を開けませんでした")
                }
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            val message = if (succeeded) {
                resources.getString(R.string.export_success, booksToExport.size)
            } else {
                resources.getString(R.string.export_failure)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val jsonExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LibraryExportFormat.JSON.mimeType),
    ) { uri -> saveExport(uri, LibraryExportFormat.JSON) }
    val csvExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LibraryExportFormat.CSV.mimeType),
    ) { uri -> saveExport(uri, LibraryExportFormat.CSV) }

    fun requestExport(format: LibraryExportFormat) {
        val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "ndc-shelf-$date.${format.extension}"
        when (format) {
            LibraryExportFormat.JSON -> jsonExporter.launch(fileName)
            LibraryExportFormat.CSV -> csvExporter.launch(fileName)
        }
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
                onUpdateCopy = viewModel::updateCopy,
                onExport = ::requestExport,
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
}
