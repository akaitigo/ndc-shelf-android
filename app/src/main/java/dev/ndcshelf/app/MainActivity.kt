package dev.ndcshelf.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.ndcshelf.app.ui.NdcShelfApp
import dev.ndcshelf.app.ui.theme.NdcShelfTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        val application = application as NdcShelfApplication
        MainViewModel.factory(
            repository = application.container.libraryRepository,
            databaseBackupManager = application.container.databaseBackupManager,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NdcShelfTheme {
                NdcShelfApp(viewModel)
            }
        }
    }
}
