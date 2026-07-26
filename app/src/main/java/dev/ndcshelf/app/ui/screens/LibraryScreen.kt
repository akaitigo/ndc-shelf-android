package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.BookDeleteUiState
import dev.ndcshelf.app.BookEditUiState
import dev.ndcshelf.app.ImportFailure
import dev.ndcshelf.app.LibraryImportUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.export.LibraryExportFormat
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportValidationError
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.BookEditField
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.ui.components.BookCover

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    books: List<LibraryBook>,
    onSaveBook: (String, BookEditDraft) -> Unit,
    onDeleteBook: (String) -> Unit,
    onExport: (LibraryExportFormat) -> Unit,
    onImport: (LibraryExportFormat) -> Unit,
    importState: LibraryImportUiState,
    bookEditState: BookEditUiState,
    onClearBookEditState: () -> Unit,
    bookDeleteState: BookDeleteUiState,
    onClearBookDeleteState: () -> Unit,
    onSelectImportPolicy: (ImportConflictPolicy) -> Unit,
    onConfirmImport: () -> Unit,
    onDismissImport: () -> Unit,
    contentPadding: PaddingValues,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedBook by remember { mutableStateOf<LibraryBook?>(null) }
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    val visibleBooks = remember(books, query) {
        books.filter { it.matches(query) }
    }

    LaunchedEffect(bookEditState) {
        val saved = bookEditState as? BookEditUiState.Saved ?: return@LaunchedEffect
        if (selectedBook?.copyId == saved.current.copyId) selectedBook = null
    }
    LaunchedEffect(bookDeleteState) {
        val deleted = bookDeleteState as? BookDeleteUiState.Deleted ?: return@LaunchedEffect
        if (selectedBook?.copyId == deleted.book.copyId) selectedBook = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "My Library",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row {
                    IconButton(
                        onClick = { showImportDialog = true },
                        enabled = importState !is LibraryImportUiState.Loading &&
                            importState !is LibraryImportUiState.Applying,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileUpload,
                            contentDescription = stringResource(R.string.import_library),
                        )
                    }
                    IconButton(
                        onClick = { showExportDialog = true },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileDownload,
                            contentDescription = stringResource(R.string.export_library),
                        )
                    }
                }
            }
            Text(
                text = "${books.size}冊の本を、ちゃんと見つけられる場所。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("タイトル・著者・ISBN・NDC・棚で検索") },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "検索をクリア")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(12.dp))
            LibrarySummary(books)
            Spacer(Modifier.height(8.dp))
        }

        if (visibleBooks.isEmpty()) {
            EmptyLibrary(
                isSearching = query.isNotBlank(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    items = visibleBooks,
                    key = LibraryBook::copyId,
                ) { book ->
                    BookCard(
                        book = book,
                        onClick = {
                            onClearBookEditState()
                            onClearBookDeleteState()
                            selectedBook = book
                        },
                    )
                }
            }
        }
    }

    selectedBook?.let { book ->
        EditBookSheet(
            book = book,
            editState = bookEditState,
            deleteState = bookDeleteState,
            onDismiss = {
                onClearBookEditState()
                onClearBookDeleteState()
                selectedBook = null
            },
            onClearErrors = onClearBookEditState,
            onSave = { draft -> onSaveBook(book.copyId, draft) },
            onDelete = { onDeleteBook(book.copyId) },
        )
    }

    if (showExportDialog) {
        ExportFormatDialog(
            onDismiss = { showExportDialog = false },
            onSelect = { format ->
                showExportDialog = false
                onExport(format)
            },
        )
    }

    if (showImportDialog) {
        ImportFormatDialog(
            onDismiss = { showImportDialog = false },
            onSelect = { format ->
                showImportDialog = false
                onImport(format)
            },
        )
    }

    when (importState) {
        LibraryImportUiState.Idle,
        is LibraryImportUiState.Success,
        -> Unit

        LibraryImportUiState.Loading -> ImportProgressDialog(
            message = stringResource(R.string.import_loading),
            onCancel = onDismissImport,
        )

        LibraryImportUiState.Applying -> ImportProgressDialog(
            message = stringResource(R.string.import_applying),
            onCancel = onDismissImport,
        )

        is LibraryImportUiState.Invalid -> ImportErrorDialog(
            errors = importState.errors,
            onDismiss = onDismissImport,
        )

        is LibraryImportUiState.Error -> ImportErrorDialog(
            errors = listOf(
                ImportValidationError(
                    recordNumber = null,
                    field = null,
                    reason = importFailureMessage(importState.failure),
                ),
            ),
            onDismiss = onDismissImport,
        )

        is LibraryImportUiState.Preview -> ImportPreviewDialog(
            state = importState,
            onSelectPolicy = onSelectImportPolicy,
            onConfirm = onConfirmImport,
            onDismiss = onDismissImport,
        )
    }
}

@Composable
private fun ImportProgressDialog(
    message: String,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { CircularProgressIndicator() },
        title = { Text(message) },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.import_cancel))
            }
        },
    )
}

@Composable
private fun ImportErrorDialog(
    errors: List<ImportValidationError>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_error_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                errors.forEach { error ->
                    val text = when {
                        error.recordNumber != null && error.field != null -> stringResource(
                            R.string.import_error_location,
                            error.recordNumber,
                            error.field,
                            error.reason,
                        )
                        error.field != null -> stringResource(
                            R.string.import_error_root,
                            error.field,
                            error.reason,
                        )
                        else -> stringResource(R.string.import_error_general, error.reason)
                    }
                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.import_close))
            }
        },
    )
}

@Composable
private fun ImportPreviewDialog(
    state: LibraryImportUiState.Preview,
    onSelectPolicy: (ImportConflictPolicy) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FileUpload, contentDescription = null) },
        title = { Text(stringResource(R.string.import_preview_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.staleRecalculated) {
                    Text(
                        text = stringResource(R.string.import_stale_notice),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(stringResource(R.string.import_preview_description))
                Text(
                    text = stringResource(
                        R.string.import_preview_counts,
                        state.addedCount,
                        state.updatedCount,
                        state.skippedCount,
                    ),
                    fontWeight = FontWeight.Bold,
                )
                if (state.warnings.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.import_warning_title),
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                    )
                    state.warnings.forEach { warning ->
                        Text(
                            text = if (warning.field == null) {
                                warning.reason
                            } else {
                                stringResource(
                                    R.string.import_error_root,
                                    warning.field,
                                    warning.reason,
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Column(Modifier.selectableGroup()) {
                    ImportPolicyOption(
                        selected = state.conflictPolicy == ImportConflictPolicy.SKIP_EXISTING,
                        label = stringResource(R.string.import_policy_skip),
                        onClick = { onSelectPolicy(ImportConflictPolicy.SKIP_EXISTING) },
                    )
                    ImportPolicyOption(
                        selected = state.conflictPolicy == ImportConflictPolicy.UPDATE_EXISTING,
                        label = stringResource(R.string.import_policy_update),
                        onClick = { onSelectPolicy(ImportConflictPolicy.UPDATE_EXISTING) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = state.changeCount > 0,
            ) {
                Text(stringResource(R.string.import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.import_cancel))
            }
        },
    )
}

@Composable
private fun importFailureMessage(failure: ImportFailure): String = when (failure) {
    ImportFailure.JSON_READ -> stringResource(R.string.import_read_failure)
    ImportFailure.CSV_READ -> stringResource(R.string.import_csv_read_failure)
    ImportFailure.PREVIEW -> stringResource(R.string.import_preview_failure)
    ImportFailure.APPLY -> stringResource(R.string.import_apply_failure)
    ImportFailure.STALE_RESELECT -> stringResource(R.string.import_stale_reselect)
}

@Composable
private fun ImportFormatDialog(
    onDismiss: () -> Unit,
    onSelect: (LibraryExportFormat) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FileUpload, contentDescription = null) },
        title = { Text(stringResource(R.string.import_dialog_title)) },
        text = { Text(stringResource(R.string.import_dialog_description)) },
        confirmButton = {
            Button(onClick = { onSelect(LibraryExportFormat.JSON) }) {
                Text(stringResource(R.string.import_json))
            }
        },
        dismissButton = {
            Button(onClick = { onSelect(LibraryExportFormat.CSV) }) {
                Text(stringResource(R.string.import_csv))
            }
        },
    )
}

@Composable
private fun ImportPolicyOption(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun ExportFormatDialog(
    onDismiss: () -> Unit,
    onSelect: (LibraryExportFormat) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
        title = { Text(stringResource(R.string.export_dialog_title)) },
        text = { Text(stringResource(R.string.export_dialog_description)) },
        confirmButton = {
            Button(onClick = { onSelect(LibraryExportFormat.JSON) }) {
                Text(stringResource(R.string.export_json))
            }
        },
        dismissButton = {
            Button(onClick = { onSelect(LibraryExportFormat.CSV) }) {
                Text(stringResource(R.string.export_csv))
            }
        },
    )
}

@Composable
private fun LibrarySummary(books: List<LibraryBook>) {
    val classified = books.count { it.ndcCode != null }
    val reading = books.count { it.readingStatus == ReadingStatus.READING }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryValue(value = books.size.toString(), label = "蔵書")
            SummaryValue(value = classified.toString(), label = "NDC分類済み")
            SummaryValue(value = reading.toString(), label = "読書中")
        }
    }
}

@Composable
private fun SummaryValue(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun BookCard(
    book: LibraryBook,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(
                coverUrl = book.coverUrl,
                title = book.title,
            )
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = book.primaryAuthor,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    book.ndcCode?.let { code ->
                        NdcBadge(
                            code = code,
                            category = book.ndcCategory?.label,
                        )
                    }
                    StatusBadge(book.readingStatus)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = book.location,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "本の情報を編集",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NdcBadge(code: String, category: String?) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = "NDC $code${category?.let { " · $it" }.orEmpty()}",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun StatusBadge(status: ReadingStatus) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = status.label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun EmptyLibrary(
    isSearching: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = if (isSearching) {
                    Icons.Rounded.Search
                } else {
                    Icons.AutoMirrored.Rounded.LibraryBooks
                },
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (isSearching) "該当する本がありません" else "最初の1冊を登録しよう",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (isSearching) {
                    "検索条件を変えてみてください"
                } else {
                    "下の「スキャン」から本のバーコードを読み取れます"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditBookSheet(
    book: LibraryBook,
    editState: BookEditUiState,
    deleteState: BookDeleteUiState,
    onDismiss: () -> Unit,
    onClearErrors: () -> Unit,
    onSave: (BookEditDraft) -> Unit,
    onDelete: () -> Unit,
) {
    var title by rememberSaveable(book.copyId) { mutableStateOf(book.title) }
    var primaryAuthor by rememberSaveable(book.copyId) { mutableStateOf(book.primaryAuthor) }
    var publisher by rememberSaveable(book.copyId) { mutableStateOf(book.publisher.orEmpty()) }
    var publishedYear by rememberSaveable(book.copyId) {
        mutableStateOf(book.publishedYear?.toString().orEmpty())
    }
    var ndcCode by rememberSaveable(book.copyId) { mutableStateOf(book.ndcCode.orEmpty()) }
    var ndcEdition by rememberSaveable(book.copyId) { mutableStateOf(book.ndcEdition.orEmpty()) }
    var location by rememberSaveable(book.copyId) { mutableStateOf(book.location) }
    var status by remember(book.copyId) { mutableStateOf(book.readingStatus) }
    var showDeleteConfirmation by rememberSaveable(book.copyId) { mutableStateOf(false) }
    val saving = editState is BookEditUiState.Saving && editState.copyId == book.copyId
    val deleting = deleteState is BookDeleteUiState.Deleting &&
        deleteState.copyId == book.copyId
    val busy = saving || deleting
    val errors = (editState as? BookEditUiState.Invalid)
        ?.takeIf { it.copyId == book.copyId }
        ?.errors
        .orEmpty()

    fun error(field: BookEditField): String? = errors.firstOrNull { it.field == field }?.reason

    fun reset() {
        title = book.title
        primaryAuthor = book.primaryAuthor
        publisher = book.publisher.orEmpty()
        publishedYear = book.publishedYear?.toString().orEmpty()
        ndcCode = book.ndcCode.orEmpty()
        ndcEdition = book.ndcEdition.orEmpty()
        location = book.location
        status = book.readingStatus
        onClearErrors()
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.book_edit_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.book_edit_isbn, book.isbn13),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_book_title)) },
                isError = error(BookEditField.TITLE) != null,
                supportingText = error(BookEditField.TITLE)?.let { message -> { Text(message) } },
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = primaryAuthor,
                onValueChange = { primaryAuthor = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_author)) },
                isError = error(BookEditField.PRIMARY_AUTHOR) != null,
                supportingText = error(BookEditField.PRIMARY_AUTHOR)?.let { message ->
                    { Text(message) }
                },
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = publisher,
                onValueChange = { publisher = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_publisher)) },
                isError = error(BookEditField.PUBLISHER) != null,
                supportingText = error(BookEditField.PUBLISHER)?.let { message -> { Text(message) } },
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = publishedYear,
                onValueChange = { publishedYear = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_published_year)) },
                isError = error(BookEditField.PUBLISHED_YEAR) != null,
                supportingText = error(BookEditField.PUBLISHED_YEAR)?.let { message ->
                    { Text(message) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = ndcCode,
                onValueChange = { ndcCode = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_ndc_code)) },
                isError = error(BookEditField.NDC_CODE) != null,
                supportingText = error(BookEditField.NDC_CODE)?.let { message -> { Text(message) } },
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = ndcEdition,
                onValueChange = { ndcEdition = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_ndc_edition)) },
                isError = error(BookEditField.NDC_EDITION) != null,
                supportingText = error(BookEditField.NDC_EDITION)?.let { message ->
                    { Text(message) }
                },
                enabled = !busy,
                singleLine = true,
            )
            Text(
                text = stringResource(
                    R.string.book_edit_classification_source,
                    book.classificationSource.name,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_location)) },
                placeholder = { Text(stringResource(R.string.book_edit_location_example)) },
                leadingIcon = { Icon(Icons.Rounded.LocationOn, contentDescription = null) },
                isError = error(BookEditField.LOCATION) != null,
                supportingText = error(BookEditField.LOCATION)?.let { message -> { Text(message) } },
                enabled = !busy,
                singleLine = true,
            )
            Text(
                text = stringResource(R.string.book_edit_reading_status),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReadingStatus.entries.forEach { candidate ->
                    FilterChip(
                        selected = status == candidate,
                        onClick = { status = candidate },
                        label = { Text(candidate.label) },
                        enabled = !busy,
                    )
                }
            }
            TextButton(
                onClick = ::reset,
                enabled = !busy,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.book_edit_reset))
            }
            Button(
                onClick = {
                    onSave(
                        BookEditDraft(
                            title = title,
                            primaryAuthor = primaryAuthor,
                            publisher = publisher,
                            publishedYear = publishedYear,
                            ndcCode = ndcCode,
                            ndcEdition = ndcEdition,
                            location = location,
                            readingStatus = status,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.book_edit_save))
                }
            }
            Button(
                onClick = { showDeleteConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                if (deleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.book_delete_action))
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.book_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.book_delete_confirm_message,
                        book.title,
                        book.isbn13,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.book_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.book_delete_cancel))
                }
            },
        )
    }
}
