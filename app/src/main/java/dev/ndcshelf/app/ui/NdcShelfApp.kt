package dev.ndcshelf.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ndcshelf.app.MainViewModel
import dev.ndcshelf.app.ui.screens.InsightsScreen
import dev.ndcshelf.app.ui.screens.LibraryScreen
import dev.ndcshelf.app.ui.screens.ScanScreen

@Composable
fun NdcShelfApp(viewModel: MainViewModel) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(AppDestination.LIBRARY) }

    Scaffold(
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
