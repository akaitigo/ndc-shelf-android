package dev.ndcshelf.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: LibraryRepository,
) : ViewModel() {
    val books: StateFlow<List<LibraryBook>> = repository.observeLibrary()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    private var lastSubmission: Pair<String, Long>? = null

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
