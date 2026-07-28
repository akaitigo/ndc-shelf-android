package dev.ndcshelf.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ndcshelf.app.ui.NdcShelfApp
import dev.ndcshelf.app.ui.theme.NdcShelfTheme

class MainActivity : ComponentActivity() {
    private var requestedEditionId by mutableStateOf<String?>(null)
    private val viewModel: MainViewModel by viewModels {
        val application = application as NdcShelfApplication
        MainViewModel.factory(
            repository = application.container.libraryRepository,
            databaseBackupManager = application.container.databaseBackupManager,
            locationRepository = application.container.locationRepository,
            librarySearchSettings = application.container.librarySearchSettings,
            seriesRepository = application.container.seriesRepository,
            workGroupRepository = application.container.workGroupRepository,
            seriesWatchRepository = application.container.seriesWatchRepository,
            seriesWatchScheduler = application.container.seriesWatchScheduler,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedEditionId = restoredBookDetailEditionId(savedInstanceState, intent)
        enableEdgeToEdge()
        setContent {
            NdcShelfTheme {
                NdcShelfApp(viewModel, requestedEditionId) { requestedEditionId = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedEditionId = intent.bookDetailEditionId()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(BOOK_DETAIL_STATE_KEY, requestedEditionId)
        super.onSaveInstanceState(outState)
    }
}

internal fun Intent.bookDetailEditionId(): String? {
    if (action != Intent.ACTION_VIEW) return null
    return data?.bookDetailEditionId()
}

internal fun restoredBookDetailEditionId(savedState: Bundle?, intent: Intent): String? =
    if (savedState?.containsKey(BOOK_DETAIL_STATE_KEY) == true) {
        savedState.getString(BOOK_DETAIL_STATE_KEY)
    } else {
        intent.bookDetailEditionId()
    }

internal fun Uri.bookDetailEditionId(): String? {
    if (scheme != BOOK_DETAIL_SCHEME || host != BOOK_DETAIL_HOST || pathSegments.size != 1) return null
    return pathSegments.single().takeIf { BOOK_DETAIL_ID.matches(it) }
}

private const val BOOK_DETAIL_SCHEME = "ndcshelf"
private const val BOOK_DETAIL_HOST = "book"
private const val BOOK_DETAIL_STATE_KEY = "requested-book-detail-edition-id"
private val BOOK_DETAIL_ID = Regex("[A-Za-z0-9._:-]{1,128}")
