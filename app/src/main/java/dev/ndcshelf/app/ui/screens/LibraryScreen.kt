package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.BookDeleteUiState
import dev.ndcshelf.app.BookEditUiState
import dev.ndcshelf.app.LocationMutationUiState
import dev.ndcshelf.app.ManualReconciliationUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.ReadingSessionUiState
import dev.ndcshelf.app.ReconciliationFailure
import dev.ndcshelf.app.ShelfMoveUiState
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.BookEditField
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibrarySort
import dev.ndcshelf.app.domain.model.LibraryStats
import dev.ndcshelf.app.domain.model.LocationLevel
import dev.ndcshelf.app.domain.model.LocationTree
import dev.ndcshelf.app.domain.model.MoveDirection
import dev.ndcshelf.app.domain.model.ReadingSession
import dev.ndcshelf.app.domain.model.ReadingSessionDraft
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.SavedSearch
import dev.ndcshelf.app.domain.model.TagNameRules
import dev.ndcshelf.app.domain.model.TagWithUsage
import dev.ndcshelf.app.domain.repository.ShelfMoveDirection
import dev.ndcshelf.app.domain.search.SearchInterpretationChip
import dev.ndcshelf.app.domain.text.UiMessage
import dev.ndcshelf.app.ui.adaptive.AdaptiveLayout
import dev.ndcshelf.app.ui.adaptive.EmptyDetailPane
import dev.ndcshelf.app.ui.components.BookCover
import dev.ndcshelf.app.ui.text.labelRes
import dev.ndcshelf.app.ui.text.resolve
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    books: List<LibraryBook>,
    searchCriteria: LibrarySearchCriteria? = null,
    searchIsCurrent: Boolean = true,
    libraryStats: LibraryStats? = null,
    onQueryChange: (String) -> Unit = {},
    interpretationChips: List<SearchInterpretationChip> = emptyList(),
    onDismissInterpretationChip: (String) -> Unit = {},
    onReadingStatusChange: (ReadingStatus?) -> Unit = {},
    onSortChange: (LibrarySort) -> Unit = {},
    onSelectedEditionChange: (String?) -> Unit = {},
    initialEditionId: String? = null,
    onInitialEditionHandled: () -> Unit = {},
    onSaveBook: (String, BookEditDraft) -> Unit,
    onDeleteBook: (String) -> Unit,
    bookEditState: BookEditUiState,
    onClearBookEditState: () -> Unit,
    bookDeleteState: BookDeleteUiState,
    onClearBookDeleteState: () -> Unit,
    locations: LocationTree,
    locationMutationState: LocationMutationUiState,
    onAddLocation: (LocationLevel, String?, String) -> Unit,
    onRenameLocation: (LocationLevel, String, String) -> Unit,
    onMoveLocation: (LocationLevel, String, MoveDirection) -> Unit,
    onDeleteLocation: (LocationLevel, String, String?, Boolean) -> Unit,
    onClearLocationState: () -> Unit,
    shelfMoveState: ShelfMoveUiState = ShelfMoveUiState.Idle,
    onMoveBookWithinTier: (String, ShelfMoveDirection) -> Unit = { _, _ -> },
    onClearShelfMoveState: () -> Unit = {},
    manualReconciliationState: ManualReconciliationUiState = ManualReconciliationUiState.Idle,
    onPreviewManualReconciliation: (String, String) -> Unit = { _, _ -> },
    onConfirmManualReconciliation: () -> Unit = {},
    onClearManualReconciliation: () -> Unit = {},
    onManageSeries: (String) -> Unit = {},
    onManageVariants: (String) -> Unit = {},
    readingSessions: List<ReadingSession> = emptyList(),
    readingSessionState: ReadingSessionUiState = ReadingSessionUiState.Idle,
    onAddReadingSession: (String, ReadingSessionDraft) -> Unit = { _, _ -> },
    onUpdateReadingSession: (String, ReadingSessionDraft) -> Unit = { _, _ -> },
    onDeleteReadingSession: (String) -> Unit = {},
    onClearReadingSessionState: () -> Unit = {},
    tags: List<TagWithUsage> = emptyList(),
    tagIdsByWork: Map<String, Set<String>> = emptyMap(),
    savedSearches: List<SavedSearch> = emptyList(),
    onToggleTagFilter: (String) -> Unit = {},
    onSetTagOnWorks: (String, Set<String>, Boolean) -> Unit = { _, _, _ -> },
    onSaveCurrentSearch: (String) -> Unit = {},
    onApplySavedSearch: (SavedSearch) -> Unit = {},
    onOpenTagManager: () -> Unit = {},
    onOpenAiLibrarian: () -> Unit = {},
    /** expanded幅で一覧と詳細を左右に並べる。判定は`NdcShelfApp`が行う。 */
    twoPane: Boolean = false,
    listPaneWidth: Dp = AdaptiveLayout.LIST_PANE_WIDTH,
    contentPadding: PaddingValues,
) {
    var localQuery by rememberSaveable { mutableStateOf("") }
    var selectedEditionId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingCopyId by rememberSaveable { mutableStateOf<String?>(null) }
    var showLocationManager by rememberSaveable { mutableStateOf(false) }
    var bulkSelectedCopyIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showBulkTagDialog by rememberSaveable { mutableStateOf(false) }
    var showSaveSearchDialog by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    // 一覧のスクロール位置は1ペイン⇔2ペインの切り替えでもリセットしないよう、
    // ペインのlambdaではなく画面本体で保持する（rememberLazyListStateはrememberSaveable）。
    val bookListState = rememberLazyListState()
    val query = searchCriteria?.query ?: localQuery
    val visibleBooks =
        remember(books, query, searchCriteria) {
            if (searchCriteria == null) books.filter { it.matches(query) } else books
        }
    val editionCounts = remember(books) { books.groupingBy { it.editionId }.eachCount() }
    val editingBook =
        remember(books, editingCopyId) {
            books.firstOrNull { it.copyId == editingCopyId }
        }
    val selectedCopies =
        remember(books, selectedEditionId) {
            books.filter { it.editionId == selectedEditionId }
        }

    fun openEditor(copyId: String) {
        onClearBookEditState()
        onClearBookDeleteState()
        onClearManualReconciliation()
        editingCopyId = copyId
    }

    LaunchedEffect(searchCriteria?.selectedEditionId) {
        searchCriteria?.let { criteria -> selectedEditionId = criteria.selectedEditionId }
    }

    LaunchedEffect(initialEditionId, books, searchIsCurrent) {
        if (initialEditionId != null) onSelectedEditionChange(initialEditionId)
        if (initialEditionId != null && searchIsCurrent) {
            if (books.any { it.editionId == initialEditionId }) {
                selectedEditionId = initialEditionId
            } else {
                selectedEditionId = null
                onSelectedEditionChange(null)
            }
            onInitialEditionHandled()
        }
    }

    LaunchedEffect(bookEditState) {
        val saved = bookEditState as? BookEditUiState.Saved ?: return@LaunchedEffect
        if (editingCopyId == saved.current.copyId) editingCopyId = null
    }
    LaunchedEffect(bookDeleteState) {
        val deleted = bookDeleteState as? BookDeleteUiState.Deleted ?: return@LaunchedEffect
        if (editingCopyId == deleted.book.copyId) editingCopyId = null
        if (books.none { it.editionId == deleted.book.editionId && it.copyId != deleted.book.copyId }) {
            selectedEditionId = null
            onSelectedEditionChange(null)
        }
    }
    LaunchedEffect(manualReconciliationState) {
        if (manualReconciliationState === ManualReconciliationUiState.Applied) {
            editingCopyId = null
            selectedEditionId = null
            onSelectedEditionChange(null)
            onClearManualReconciliation()
        }
    }

    val isDetailLoading = !searchIsCurrent && searchCriteria?.selectedEditionId != null
    val showDetail = searchIsCurrent && selectedCopies.isNotEmpty()

    val detailPane: @Composable (Modifier) -> Unit = { paneModifier ->
        when {
            isDetailLoading -> {
                Box(
                    modifier = paneModifier,
                    contentAlignment = Alignment.Center,
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().testTag(LIBRARY_SEARCH_PROGRESS_TAG),
                    )
                }
            }

            showDetail -> {
                Box(modifier = paneModifier) {
                    BookDetailScreen(
                        copies = selectedCopies,
                        onBack = {
                            selectedEditionId = null
                            onSelectedEditionChange(null)
                        },
                        onEditCopy = ::openEditor,
                        onEditBibliography = { openEditor(selectedCopies.first().copyId) },
                        onReconcile = { openEditor(selectedCopies.first().copyId) },
                        onManageSeries = onManageSeries,
                        onManageVariants = onManageVariants,
                        readingSessions = readingSessions,
                        readingSessionState = readingSessionState,
                        onAddReadingSession = onAddReadingSession,
                        onUpdateReadingSession = onUpdateReadingSession,
                        onDeleteReadingSession = onDeleteReadingSession,
                        onClearReadingSessionState = onClearReadingSessionState,
                        tags = tags,
                        tagIdsByWork = tagIdsByWork,
                        onSetTagOnWorks = onSetTagOnWorks,
                        showBackAction = !twoPane,
                        contentPadding = contentPadding,
                    )
                }
            }

            else -> {
                EmptyDetailPane(
                    message = stringResource(R.string.library_detail_pane_empty),
                    modifier = paneModifier,
                    contentPadding = contentPadding,
                )
            }
        }
    }

    val listPane: @Composable (Modifier) -> Unit = { paneModifier ->
        Column(
            modifier = paneModifier.padding(top = contentPadding.calculateTopPadding()),
        ) {
            if (showHelp) {
                LibraryHelpDialog(onDismiss = { showHelp = false })
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.library_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconButton(onClick = { showHelp = true }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.HelpOutline,
                            contentDescription = stringResource(R.string.library_help_button),
                        )
                    }
                }
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.library_subtitle,
                            libraryStats?.totalCount ?: books.size,
                            libraryStats?.totalCount ?: books.size,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { value ->
                        localQuery = value
                        onQueryChange(value)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                localQuery = ""
                                onQueryChange("")
                            }) {
                                Icon(
                                    Icons.Rounded.Clear,
                                    contentDescription = stringResource(R.string.library_search_clear),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                )
                InterpretationChipsRow(
                    chips = interpretationChips,
                    onDismissChip = onDismissInterpretationChip,
                )
                Spacer(Modifier.height(12.dp))
                LibrarySummary(libraryStats ?: books.toStats())
                LibrarySearchControls(
                    status = searchCriteria?.readingStatus,
                    sort = searchCriteria?.sort ?: LibrarySort.ADDED_NEWEST,
                    onStatusChange = onReadingStatusChange,
                    onSortChange = onSortChange,
                )
                LibraryTagFilters(
                    tags = tags,
                    selectedTagIds = searchCriteria?.tagIds.orEmpty(),
                    savedSearches = savedSearches,
                    onToggleTagFilter = onToggleTagFilter,
                    onApplySavedSearch = onApplySavedSearch,
                    onSaveCurrentSearch = { showSaveSearchDialog = true },
                )
                TextButton(
                    onClick = { showLocationManager = true },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Icon(Icons.Rounded.LocationOn, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.location_manage_action))
                }
                TextButton(
                    onClick = onOpenTagManager,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.tag_manage_action))
                }
                TextButton(
                    onClick = onOpenAiLibrarian,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.ai_librarian_open_action))
                }
                if (bulkSelectedCopyIds.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 選択件数は長押しのたびに変わるため、live regionで自動読み上げする。
                        Text(
                            pluralStringResource(
                                R.plurals.tag_bulk_selected_count,
                                bulkSelectedCopyIds.size,
                                bulkSelectedCopyIds.size,
                            ),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        TextButton(onClick = { showBulkTagDialog = true }) {
                            Text(stringResource(R.string.tag_bulk_edit_action))
                        }
                        TextButton(onClick = { bulkSelectedCopyIds = emptyList() }) {
                            Text(stringResource(R.string.tag_bulk_clear_action))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (!searchIsCurrent) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().testTag(LIBRARY_SEARCH_PROGRESS_TAG),
                    )
                }
            } else if (visibleBooks.isEmpty()) {
                EmptyLibrary(
                    isSearching = query.isNotBlank(),
                    hasInterpretation = interpretationChips.isNotEmpty(),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = contentPadding.calculateBottomPadding()),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(LIBRARY_LIST_TEST_TAG),
                    state = bookListState,
                    contentPadding =
                        PaddingValues(
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
                            editionCopyCount = editionCounts[book.editionId] ?: 1,
                            selected = book.copyId in bulkSelectedCopyIds,
                            bulkSelectionActive = bulkSelectedCopyIds.isNotEmpty(),
                            openInDetailPane = twoPane && book.editionId == selectedEditionId,
                            onClick = {
                                if (bulkSelectedCopyIds.isNotEmpty()) {
                                    bulkSelectedCopyIds =
                                        if (book.copyId in bulkSelectedCopyIds) {
                                            bulkSelectedCopyIds - book.copyId
                                        } else {
                                            bulkSelectedCopyIds + book.copyId
                                        }
                                } else {
                                    selectedEditionId = book.editionId
                                    onSelectedEditionChange(book.editionId)
                                }
                            },
                            onLongClick = {
                                bulkSelectedCopyIds =
                                    if (book.copyId in bulkSelectedCopyIds) {
                                        bulkSelectedCopyIds - book.copyId
                                    } else {
                                        bulkSelectedCopyIds + book.copyId
                                    }
                            },
                        )
                    }
                }
            }
        }
    }

    // 読み上げ順・Tabフォーカス順は一覧ペイン→詳細ペインを維持する。
    // 各ペインの呼び出し箇所を1つに保つことで、1ペイン⇔2ペインの切り替えでも
    // ペイン内部のrememberSaveable（スクロール位置など）を失わない。
    val detailVisible = isDetailLoading || showDetail
    Row(modifier = Modifier.fillMaxSize()) {
        if (twoPane || !detailVisible) {
            listPane(
                if (twoPane) {
                    Modifier.width(listPaneWidth).fillMaxHeight()
                } else {
                    Modifier.fillMaxSize()
                },
            )
        }
        if (twoPane) {
            VerticalDivider()
        }
        if (twoPane || detailVisible) {
            detailPane(
                if (twoPane) {
                    Modifier.weight(1f).fillMaxHeight()
                } else {
                    Modifier.fillMaxSize()
                },
            )
        }
    }

    editingBook?.let { book ->
        EditBookSheet(
            book = book,
            editionCopyCount = editionCounts[book.editionId] ?: 1,
            editState = bookEditState,
            deleteState = bookDeleteState,
            locations = locations,
            allBooks = books,
            shelfMoveState = shelfMoveState,
            onDismiss = {
                onClearBookEditState()
                onClearBookDeleteState()
                onClearShelfMoveState()
                onClearManualReconciliation()
                editingCopyId = null
            },
            onClearErrors = onClearBookEditState,
            onSave = { draft -> onSaveBook(book.copyId, draft) },
            onDelete = { onDeleteBook(book.copyId) },
            onMoveWithinTier = { direction -> onMoveBookWithinTier(book.copyId, direction) },
            onClearShelfMoveState = onClearShelfMoveState,
            reconciliationState = manualReconciliationState,
            onPreviewReconciliation = { isbn ->
                onPreviewManualReconciliation(book.copyId, isbn)
            },
            onConfirmReconciliation = onConfirmManualReconciliation,
            onClearReconciliation = onClearManualReconciliation,
        )
    }

    if (showLocationManager) {
        LocationManagerSheet(
            tree = locations,
            mutationState = locationMutationState,
            onDismiss = {
                onClearLocationState()
                showLocationManager = false
            },
            onAdd = onAddLocation,
            onRename = onRenameLocation,
            onMove = onMoveLocation,
            onDelete = onDeleteLocation,
            onClearState = onClearLocationState,
        )
    }

    if (showSaveSearchDialog) {
        NameInputDialog(
            title = stringResource(R.string.saved_search_save_title),
            label = stringResource(R.string.saved_search_name, TagNameRules.MAX_NAME_LENGTH),
            initialValue = "",
            onSave = { name ->
                onSaveCurrentSearch(name)
                showSaveSearchDialog = false
            },
            onDismiss = { showSaveSearchDialog = false },
        )
    }

    if (showBulkTagDialog) {
        val selectedWorkIds =
            books
                .filter { it.copyId in bulkSelectedCopyIds }
                .mapTo(linkedSetOf(), LibraryBook::workId)
        BulkTagDialog(
            tags = tags,
            selectedWorkIds = selectedWorkIds,
            tagIdsByWork = tagIdsByWork,
            onSetTagOnWorks = onSetTagOnWorks,
            onDismiss = { showBulkTagDialog = false },
        )
    }
}

/**
 * 自然言語検索の解釈結果チップ。各チップは×で個別に解除でき、解除した語は
 * 通常の部分一致検索へ戻る。ラベル（タグ名等を含む）はプレーンテキストとして表示する。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterpretationChipsRow(
    chips: List<SearchInterpretationChip>,
    onDismissChip: (String) -> Unit,
) {
    if (chips.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.nl_search_interpretation_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { chip ->
            InputChip(
                selected = true,
                onClick = { onDismissChip(chip.id) },
                modifier = Modifier.testTag(interpretationChipTag(chip.id)),
                label = { Text(chip.label.resolve()) },
                trailingIcon = {
                    Icon(
                        Icons.Rounded.Clear,
                        contentDescription =
                            stringResource(
                                R.string.nl_search_chip_dismiss,
                                chip.label.resolve(),
                            ),
                        modifier = Modifier.size(InputChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

/** タグ絞り込みチップと保存済み検索。タグ名は常にプレーンテキストとして表示する。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryTagFilters(
    tags: List<TagWithUsage>,
    selectedTagIds: Set<String>,
    savedSearches: List<SavedSearch>,
    onToggleTagFilter: (String) -> Unit,
    onApplySavedSearch: (SavedSearch) -> Unit,
    onSaveCurrentSearch: () -> Unit,
) {
    if (tags.isNotEmpty()) {
        Text(
            stringResource(R.string.tag_filter_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { tagWithUsage ->
                FilterChip(
                    selected = tagWithUsage.tag.id in selectedTagIds,
                    onClick = { onToggleTagFilter(tagWithUsage.tag.id) },
                    leadingIcon = { TagColorSwatch(tagWithUsage.tag.colorRole) },
                    label = { Text(tagWithUsage.tag.name) },
                )
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (savedSearches.isNotEmpty()) {
            Text(
                stringResource(R.string.saved_search_apply_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onSaveCurrentSearch) {
            Text(stringResource(R.string.saved_search_save_action))
        }
    }
    if (savedSearches.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            savedSearches.forEach { savedSearch ->
                FilterChip(
                    selected = false,
                    onClick = { onApplySavedSearch(savedSearch) },
                    label = { Text(savedSearch.name) },
                )
            }
        }
    }
}

@Composable
private fun BulkTagDialog(
    tags: List<TagWithUsage>,
    selectedWorkIds: Set<String>,
    tagIdsByWork: Map<String, Set<String>>,
    onSetTagOnWorks: (String, Set<String>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.tag_bulk_dialog_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.tag_bulk_dialog_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (tags.isEmpty()) {
                    Text(
                        stringResource(R.string.tag_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                tags.forEach { tagWithUsage ->
                    val assignedToAll =
                        selectedWorkIds.isNotEmpty() &&
                            selectedWorkIds.all { workId ->
                                tagWithUsage.tag.id in tagIdsByWork[workId].orEmpty()
                            }
                    // Checkbox単体ではどのタグか読み上げられないため、行全体を
                    // toggleableにしてタグ名とチェック状態を1ノードへ統合する。
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = assignedToAll,
                                    role = Role.Checkbox,
                                    onValueChange = { checked ->
                                        onSetTagOnWorks(tagWithUsage.tag.id, selectedWorkIds, checked)
                                    },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = assignedToAll, onCheckedChange = null)
                        TagColorSwatch(tagWithUsage.tag.colorRole)
                        Spacer(Modifier.width(6.dp))
                        Text(tagWithUsage.tag.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.tag_bulk_close))
            }
        },
    )
}

internal const val LIBRARY_SEARCH_PROGRESS_TAG = "library-search-progress"

/** 本棚一覧ペインのLazyColumn。スクロール位置の回帰テストで使う。 */
internal const val LIBRARY_LIST_TEST_TAG = "library-list"

internal fun interpretationChipTag(chipId: String): String = "nl-search-chip-$chipId"

@Composable
private fun LibrarySummary(stats: LibraryStats) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryValue(
                value = stats.totalCount.toString(),
                label = stringResource(R.string.library_summary_total),
            )
            SummaryValue(
                value = stats.classifiedCount.toString(),
                label = stringResource(R.string.library_summary_classified),
            )
            SummaryValue(
                value = stats.readingCount.toString(),
                label = stringResource(R.string.library_summary_reading),
            )
        }
    }
}

private fun List<LibraryBook>.toStats() =
    LibraryStats(
        totalCount = size,
        classifiedCount = count { it.ndcCode != null },
        readingCount = count { it.readingStatus == ReadingStatus.READING },
    )

@Composable
private fun LibrarySearchControls(
    status: ReadingStatus?,
    sort: LibrarySort,
    onStatusChange: (ReadingStatus?) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.library_filter_status),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = status == null,
                onClick = { onStatusChange(null) },
                label = { Text(stringResource(R.string.library_filter_all)) },
            )
            ReadingStatus.entries.forEach { candidate ->
                FilterChip(
                    selected = status == candidate,
                    onClick = { onStatusChange(candidate) },
                    label = { Text(stringResource(candidate.labelRes)) },
                )
            }
        }
        Text(
            stringResource(R.string.library_sort_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibrarySort.entries.forEach { candidate ->
                FilterChip(
                    selected = sort == candidate,
                    onClick = { onSortChange(candidate) },
                    label = { Text(candidate.label()) },
                )
            }
        }
    }
}

@Composable
private fun LibrarySort.label(): String =
    stringResource(
        when (this) {
            LibrarySort.ADDED_NEWEST -> R.string.library_sort_added
            LibrarySort.TITLE -> R.string.library_sort_title
            LibrarySort.AUTHOR -> R.string.library_sort_author
            LibrarySort.NDC -> R.string.library_sort_ndc
            LibrarySort.SHELF -> R.string.library_sort_shelf
        },
    )

@Composable
private fun SummaryValue(
    value: String,
    label: String,
) {
    // 値とラベルが別々に読み上げられないよう「蔵書: 12」の形で1ノードへ統合する。
    val description = stringResource(R.string.library_summary_value, label, value)
    Column(
        modifier =
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = description
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: LibraryBook,
    editionCopyCount: Int,
    onClick: () -> Unit,
    selected: Boolean = false,
    bulkSelectionActive: Boolean = false,
    /** 2ペイン表示で、この本が右の詳細ペインに表示されている。 */
    openInDetailPane: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    val selectedLabel = stringResource(R.string.book_card_selected)
    val unselectedLabel = stringResource(R.string.book_card_unselected)
    val openInDetailLabel = stringResource(R.string.book_card_open_in_detail)
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when {
                        selected -> MaterialTheme.colorScheme.secondaryContainer
                        openInDetailPane -> MaterialTheme.colorScheme.surfaceContainerHighest
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClickLabel = stringResource(R.string.book_card_open_label),
                        onLongClickLabel = stringResource(R.string.book_card_bulk_select_label),
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    // 一括選択の状態は背景色だけで示さず、selected+stateDescriptionで
                    // スクリーンリーダーへ伝える（可視のチェックアイコンも併記する）。
                    .semantics {
                        if (bulkSelectionActive) {
                            this.selected = selected
                            stateDescription = if (selected) selectedLabel else unselectedLabel
                        } else if (openInDetailPane) {
                            // 2ペインでは色だけでなく状態としても「詳細表示中」を伝える。
                            this.selected = true
                            stateDescription = openInDetailLabel
                        }
                    }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bulkSelectionActive) {
                Icon(
                    imageVector =
                        if (selected) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.RadioButtonUnchecked
                        },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint =
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Spacer(Modifier.width(8.dp))
            }
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
                Text(
                    text =
                        "${book.copyLabel} ・ " +
                            pluralStringResource(R.plurals.book_copy_count, editionCopyCount, editionCopyCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    book.ndcCode?.let { code ->
                        NdcBadge(
                            code = code,
                            category = book.ndcCategory?.let { stringResource(it.labelRes) },
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
            // 行全体のクリックで編集が開くため（onClickLabelで告知済み）、
            // 鉛筆アイコンは装飾扱いにして読み上げの重複を避ける。
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NdcBadge(
    code: String,
    category: String?,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text =
                category
                    ?.let { stringResource(R.string.library_ndc_chip_with_category, code, it) }
                    ?: stringResource(R.string.library_ndc_chip, code),
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
            text = stringResource(status.labelRes),
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
    hasInterpretation: Boolean = false,
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
                imageVector =
                    if (isSearching) {
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
                text =
                    stringResource(
                        if (isSearching) {
                            R.string.library_empty_searching_title
                        } else {
                            R.string.library_empty_title
                        },
                    ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    when {
                        hasInterpretation -> stringResource(R.string.nl_search_empty_hint)
                        isSearching -> stringResource(R.string.library_empty_searching_body)
                        else -> stringResource(R.string.library_empty_body)
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
    editionCopyCount: Int,
    locations: LocationTree,
    allBooks: List<LibraryBook>,
    shelfMoveState: ShelfMoveUiState,
    editState: BookEditUiState,
    deleteState: BookDeleteUiState,
    onDismiss: () -> Unit,
    onClearErrors: () -> Unit,
    onSave: (BookEditDraft) -> Unit,
    onDelete: () -> Unit,
    onMoveWithinTier: (ShelfMoveDirection) -> Unit,
    onClearShelfMoveState: () -> Unit,
    reconciliationState: ManualReconciliationUiState,
    onPreviewReconciliation: (String) -> Unit,
    onConfirmReconciliation: () -> Unit,
    onClearReconciliation: () -> Unit,
) {
    var title by rememberSaveable(book.copyId) { mutableStateOf(book.title) }
    var copyLabel by rememberSaveable(book.copyId) { mutableStateOf(book.copyLabel) }
    var primaryAuthor by rememberSaveable(book.copyId) { mutableStateOf(book.primaryAuthor) }
    var publisher by rememberSaveable(book.copyId) { mutableStateOf(book.publisher.orEmpty()) }
    var publishedYear by rememberSaveable(book.copyId) {
        mutableStateOf(book.publishedYear?.toString().orEmpty())
    }
    var ndcCode by rememberSaveable(book.copyId) { mutableStateOf(book.ndcCode.orEmpty()) }
    var ndcEdition by rememberSaveable(book.copyId) { mutableStateOf(book.ndcEdition.orEmpty()) }
    var location by rememberSaveable(book.copyId) { mutableStateOf(book.location) }
    var locationTierId by rememberSaveable(book.copyId) { mutableStateOf(book.locationTierId) }
    var insertAfterCopyId by rememberSaveable(book.copyId) { mutableStateOf<String?>(null) }
    var insertAtStart by rememberSaveable(book.copyId) { mutableStateOf(false) }
    var positionSpecified by rememberSaveable(book.copyId) { mutableStateOf(false) }
    var status by remember(book.copyId) { mutableStateOf(book.readingStatus) }
    var showDeleteConfirmation by rememberSaveable(book.copyId) { mutableStateOf(false) }
    var reconciliationIsbn by rememberSaveable(book.copyId) { mutableStateOf(book.isbn13.orEmpty()) }
    val saving = editState is BookEditUiState.Saving && editState.copyId == book.copyId
    val deleting =
        deleteState is BookDeleteUiState.Deleting &&
            deleteState.copyId == book.copyId
    val reconciling =
        reconciliationState === ManualReconciliationUiState.Loading ||
            reconciliationState is ManualReconciliationUiState.Applying
    val busy = saving || deleting || reconciling
    val moving = shelfMoveState is ShelfMoveUiState.Moving && shelfMoveState.copyId == book.copyId
    val errors =
        (editState as? BookEditUiState.Invalid)
            ?.takeIf { it.copyId == book.copyId }
            ?.errors
            .orEmpty()
    val formattedAddedAt =
        remember(book.addedAt) {
            DateFormat
                .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(book.addedAt))
        }
    val unsetLocation = stringResource(R.string.location_unset_value)
    val orderedTierBooks =
        remember(allBooks, book.locationTierId) {
            allBooks
                .filter { it.locationTierId == book.locationTierId && it.locationTierId != null }
                .sortedWith(
                    compareBy<LibraryBook> { it.shelfOrderKey == null }
                        .thenBy { it.shelfOrderKey }
                        .thenBy { it.addedAt }
                        .thenBy { it.copyId },
                )
        }
    val currentShelfIndex = orderedTierBooks.indexOfFirst { it.copyId == book.copyId }
    val leftNeighbor = orderedTierBooks.getOrNull(currentShelfIndex - 1)
    val rightNeighbor = orderedTierBooks.getOrNull(currentShelfIndex + 1)
    val candidateBooksByTier =
        remember(allBooks, book.copyId) {
            allBooks
                .asSequence()
                .filter { it.locationTierId != null && it.copyId != book.copyId }
                .groupBy { requireNotNull(it.locationTierId) }
                .mapValues { (_, candidates) ->
                    candidates.sortedWith(
                        compareBy<LibraryBook> { it.shelfOrderKey == null }
                            .thenBy { it.shelfOrderKey }
                            .thenBy { it.addedAt }
                            .thenBy { it.copyId },
                    )
                }
        }
    val targetTierBooks = locationTierId?.let(candidateBooksByTier::get).orEmpty()

    fun error(field: BookEditField): UiMessage? = errors.firstOrNull { it.field == field }?.reason

    fun reset() {
        title = book.title
        copyLabel = book.copyLabel
        primaryAuthor = book.primaryAuthor
        publisher = book.publisher.orEmpty()
        publishedYear = book.publishedYear?.toString().orEmpty()
        ndcCode = book.ndcCode.orEmpty()
        ndcEdition = book.ndcEdition.orEmpty()
        location = book.location
        locationTierId = book.locationTierId
        insertAfterCopyId = null
        insertAtStart = false
        positionSpecified = false
        status = book.readingStatus
        onClearErrors()
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier =
                Modifier
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
            if (book.bibliographicSource == BibliographicSource.MANUAL) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.reconciliation_manual_notice),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = reconciliationIsbn,
                            onValueChange = {
                                reconciliationIsbn = it
                                onClearReconciliation()
                            },
                            label = { Text(stringResource(R.string.reconciliation_isbn)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onPreviewReconciliation(reconciliationIsbn) },
                            enabled = !busy,
                        ) {
                            if (reconciliationState === ManualReconciliationUiState.Loading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Text(stringResource(R.string.reconciliation_lookup))
                            }
                        }
                        (reconciliationState as? ManualReconciliationUiState.Error)?.let { state ->
                            Text(
                                text =
                                    stringResource(
                                        when (state.failure) {
                                            ReconciliationFailure.INVALID_ISBN -> R.string.reconciliation_invalid
                                            ReconciliationFailure.NOT_FOUND -> R.string.reconciliation_not_found
                                            ReconciliationFailure.LOOKUP -> R.string.reconciliation_lookup_error
                                            ReconciliationFailure.CONFLICT -> R.string.reconciliation_conflict
                                            ReconciliationFailure.SAVE -> R.string.reconciliation_save_error
                                        },
                                    ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            Text(
                text = stringResource(R.string.book_edit_isbn, book.isbn13 ?: stringResource(R.string.book_detail_no_isbn)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.book_copy_details),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.book_added_at, formattedAddedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = pluralStringResource(R.plurals.book_copy_count, editionCopyCount, editionCopyCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = copyLabel,
                onValueChange = { copyLabel = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_copy_label)) },
                isError = error(BookEditField.COPY_LABEL) != null,
                supportingText = error(BookEditField.COPY_LABEL)?.let { message -> { Text(message.resolve()) } },
                enabled = !busy,
                singleLine = true,
            )
            Text(
                text = stringResource(R.string.book_edition_details),
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_book_title)) },
                isError = error(BookEditField.TITLE) != null,
                supportingText = error(BookEditField.TITLE)?.let { message -> { Text(message.resolve()) } },
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = primaryAuthor,
                onValueChange = { primaryAuthor = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_author)) },
                isError = error(BookEditField.PRIMARY_AUTHOR) != null,
                supportingText =
                    error(BookEditField.PRIMARY_AUTHOR)?.let { message ->
                        { Text(message.resolve()) }
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
                supportingText = error(BookEditField.PUBLISHER)?.let { message -> { Text(message.resolve()) } },
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = publishedYear,
                onValueChange = { publishedYear = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_published_year)) },
                isError = error(BookEditField.PUBLISHED_YEAR) != null,
                supportingText =
                    error(BookEditField.PUBLISHED_YEAR)?.let { message ->
                        { Text(message.resolve()) }
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
                supportingText = error(BookEditField.NDC_CODE)?.let { message -> { Text(message.resolve()) } },
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = ndcEdition,
                onValueChange = { ndcEdition = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_ndc_edition)) },
                isError = error(BookEditField.NDC_EDITION) != null,
                supportingText =
                    error(BookEditField.NDC_EDITION)?.let { message ->
                        { Text(message.resolve()) }
                    },
                enabled = !busy,
                singleLine = true,
            )
            Text(
                text =
                    stringResource(
                        R.string.book_edit_classification_source,
                        book.classificationSource.name,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = location,
                onValueChange = {
                    location = it
                    locationTierId = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.book_edit_location)) },
                placeholder = { Text(stringResource(R.string.book_edit_location_example)) },
                leadingIcon = { Icon(Icons.Rounded.LocationOn, contentDescription = null) },
                isError = error(BookEditField.LOCATION) != null,
                supportingText = error(BookEditField.LOCATION)?.let { message -> { Text(message.resolve()) } },
                enabled = !busy && locationTierId == null,
                singleLine = true,
            )
            Text(stringResource(R.string.location_registered_tier), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = locationTierId == null,
                    onClick = {
                        locationTierId = null
                        positionSpecified = true
                        location = unsetLocation
                    },
                    label = { Text(stringResource(R.string.location_unset_or_free)) },
                    enabled = !busy,
                )
                locations.tiers.forEach { tier ->
                    val path = locations.pathForTier(tier.id) ?: tier.name
                    val candidates = candidateBooksByTier[tier.id].orEmpty()
                    FilterChip(
                        selected = locationTierId == tier.id,
                        onClick = {
                            locationTierId = tier.id
                            location = path
                            positionSpecified = true
                            insertAtStart = candidates.isEmpty()
                            insertAfterCopyId = candidates.lastOrNull()?.copyId
                        },
                        label = { Text(path) },
                        enabled = !busy,
                    )
                }
            }
            if (locationTierId != null) {
                Text(
                    stringResource(R.string.shelf_order_insert_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = positionSpecified && insertAtStart,
                        onClick = {
                            positionSpecified = true
                            insertAtStart = true
                            insertAfterCopyId = null
                        },
                        label = { Text(stringResource(R.string.shelf_order_insert_start)) },
                        enabled = !busy,
                    )
                    targetTierBooks.forEach { candidate ->
                        FilterChip(
                            selected = positionSpecified && insertAfterCopyId == candidate.copyId,
                            onClick = {
                                positionSpecified = true
                                insertAtStart = false
                                insertAfterCopyId = candidate.copyId
                            },
                            label = {
                                Text(stringResource(R.string.shelf_order_insert_after, candidate.title))
                            },
                            enabled = !busy,
                        )
                    }
                }
            }
            if (book.locationTierId != null) {
                Text(stringResource(R.string.shelf_order_title), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(
                        R.string.shelf_order_left_neighbor,
                        leftNeighbor?.title ?: stringResource(R.string.shelf_order_edge),
                    ),
                )
                Text(
                    stringResource(
                        R.string.shelf_order_right_neighbor,
                        rightNeighbor?.title ?: stringResource(R.string.shelf_order_edge),
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onClearShelfMoveState()
                            onMoveWithinTier(ShelfMoveDirection.LEFT)
                        },
                        enabled = leftNeighbor != null && !moving,
                    ) { Text(stringResource(R.string.shelf_order_move_left)) }
                    Button(
                        onClick = {
                            onClearShelfMoveState()
                            onMoveWithinTier(ShelfMoveDirection.RIGHT)
                        },
                        enabled = rightNeighbor != null && !moving,
                    ) { Text(stringResource(R.string.shelf_order_move_right)) }
                }
                (shelfMoveState as? ShelfMoveUiState.Error)?.let { Text(it.message.resolve()) }
            }
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
                        label = { Text(stringResource(candidate.labelRes)) },
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
                            locationTierId = locationTierId,
                            locationInsertAfterCopyId = insertAfterCopyId,
                            locationInsertAtStart = insertAtStart,
                            locationPositionSpecified = positionSpecified,
                            copyLabel = copyLabel,
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
                colors =
                    ButtonDefaults.buttonColors(
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
                        book.isbn13 ?: stringResource(R.string.book_detail_no_isbn),
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                    colors =
                        ButtonDefaults.buttonColors(
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

    val preview =
        when (reconciliationState) {
            is ManualReconciliationUiState.Preview -> reconciliationState.preview
            is ManualReconciliationUiState.Applying -> reconciliationState.preview
            else -> null
        }
    if (preview != null && preview.current.copyId == book.copyId) {
        AlertDialog(
            onDismissRequest = { if (!reconciling) onClearReconciliation() },
            title = { Text(stringResource(R.string.reconciliation_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.reconciliation_preview_warning))
                    val none = stringResource(R.string.reconciliation_diff_none)
                    Text(
                        stringResource(
                            R.string.reconciliation_diff_title,
                            book.title,
                            preview.candidate.title,
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.reconciliation_diff_author,
                            book.primaryAuthor,
                            preview.candidate.primaryAuthor,
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.reconciliation_diff_isbn,
                            book.isbn13 ?: none,
                            preview.candidate.isbn13,
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.reconciliation_diff_publisher,
                            book.publisher ?: none,
                            preview.candidate.publisher ?: none,
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.reconciliation_diff_year,
                            book.publishedYear?.toString() ?: none,
                            preview.candidate.publishedYear?.toString() ?: none,
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.reconciliation_diff_ndc,
                            book.ndcCode ?: none,
                            preview.candidate.ndcCode ?: none,
                        ),
                    )
                    if (preview.existingEditionId != null) {
                        Text(
                            pluralStringResource(
                                R.plurals.reconciliation_merge_notice,
                                preview.existingCopyCount,
                                preview.existingCopyCount,
                                preview.currentEditionCopyCount,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (preview.existingEditionId == null && preview.currentEditionCopyCount > 1) {
                        Text(
                            pluralStringResource(
                                R.plurals.reconciliation_shared_notice,
                                preview.currentEditionCopyCount,
                                preview.currentEditionCopyCount,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(enabled = !reconciling, onClick = onConfirmReconciliation) {
                    Text(stringResource(R.string.reconciliation_confirm))
                }
            },
            dismissButton = {
                TextButton(enabled = !reconciling, onClick = onClearReconciliation) {
                    Text(stringResource(R.string.import_cancel))
                }
            },
        )
    }
}

private data class LocationEditorTarget(
    val level: LocationLevel,
    val id: String? = null,
    val parentId: String? = null,
    val initialName: String = "",
)

private data class LocationDeleteTarget(
    val level: LocationLevel,
    val id: String,
    val name: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationManagerSheet(
    tree: LocationTree,
    mutationState: LocationMutationUiState,
    onDismiss: () -> Unit,
    onAdd: (LocationLevel, String?, String) -> Unit,
    onRename: (LocationLevel, String, String) -> Unit,
    onMove: (LocationLevel, String, MoveDirection) -> Unit,
    onDelete: (LocationLevel, String, String?, Boolean) -> Unit,
    onClearState: () -> Unit,
) {
    var editor by remember { mutableStateOf<LocationEditorTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<LocationDeleteTarget?>(null) }
    val busy = mutationState === LocationMutationUiState.Working

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.location_manager_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.location_manager_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { editor = LocationEditorTarget(LocationLevel.ROOM) },
                enabled = !busy,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Text(stringResource(R.string.location_add_room))
            }
            if (tree.rooms.isEmpty()) {
                Text(stringResource(R.string.location_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            tree.rooms.forEach { room ->
                LocationItemRow(
                    name = room.name,
                    detail = stringResource(R.string.location_level_room),
                    enabled = !busy,
                    onEdit = { editor = LocationEditorTarget(LocationLevel.ROOM, room.id, initialName = room.name) },
                    onUp = { onMove(LocationLevel.ROOM, room.id, MoveDirection.UP) },
                    onDown = { onMove(LocationLevel.ROOM, room.id, MoveDirection.DOWN) },
                    onDelete = {
                        deleteTarget = LocationDeleteTarget(LocationLevel.ROOM, room.id, room.name)
                    },
                )
                TextButton(
                    onClick = { editor = LocationEditorTarget(LocationLevel.SHELF, parentId = room.id) },
                    enabled = !busy,
                    modifier = Modifier.padding(start = 20.dp),
                ) { Text(stringResource(R.string.location_add_shelf)) }
                room.shelves.forEach { shelf ->
                    Column(modifier = Modifier.padding(start = 20.dp)) {
                        LocationItemRow(
                            name = shelf.name,
                            detail = stringResource(R.string.location_level_shelf),
                            enabled = !busy,
                            onEdit = { editor = LocationEditorTarget(LocationLevel.SHELF, shelf.id, initialName = shelf.name) },
                            onUp = { onMove(LocationLevel.SHELF, shelf.id, MoveDirection.UP) },
                            onDown = { onMove(LocationLevel.SHELF, shelf.id, MoveDirection.DOWN) },
                            onDelete = {
                                deleteTarget = LocationDeleteTarget(LocationLevel.SHELF, shelf.id, shelf.name)
                            },
                        )
                        TextButton(
                            onClick = { editor = LocationEditorTarget(LocationLevel.TIER, parentId = shelf.id) },
                            enabled = !busy,
                            modifier = Modifier.padding(start = 20.dp),
                        ) { Text(stringResource(R.string.location_add_tier)) }
                        shelf.tiers.forEach { tier ->
                            LocationItemRow(
                                name = tier.name,
                                detail = pluralStringResource(R.plurals.location_copy_count, tier.copyCount, tier.copyCount),
                                enabled = !busy,
                                modifier = Modifier.padding(start = 20.dp),
                                onEdit = { editor = LocationEditorTarget(LocationLevel.TIER, tier.id, initialName = tier.name) },
                                onUp = { onMove(LocationLevel.TIER, tier.id, MoveDirection.UP) },
                                onDown = { onMove(LocationLevel.TIER, tier.id, MoveDirection.DOWN) },
                                onDelete = {
                                    deleteTarget = LocationDeleteTarget(LocationLevel.TIER, tier.id, tier.name)
                                },
                            )
                        }
                    }
                }
            }
            (mutationState as? LocationMutationUiState.Error)?.let { state ->
                Text(state.message.resolve(), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    editor?.let { target ->
        LocationNameDialog(
            target = target,
            onDismiss = { editor = null },
            onConfirm = { name ->
                if (target.id == null) {
                    onAdd(target.level, target.parentId, name)
                } else {
                    onRename(target.level, target.id, name)
                }
                editor = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.location_delete_title)) },
            text = { Text(stringResource(R.string.location_delete_message, target.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(target.level, target.id, null, false)
                        deleteTarget = null
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) { Text(stringResource(R.string.location_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.location_cancel))
                }
            },
        )
    }

    (mutationState as? LocationMutationUiState.InUse)?.let { state ->
        var replacementTierId by remember(state.id) { mutableStateOf<String?>(null) }
        val excludedTierIds =
            when (state.level) {
                LocationLevel.ROOM -> {
                    tree.rooms
                        .firstOrNull { it.id == state.id }
                        ?.shelves
                        .orEmpty()
                        .flatMap { it.tiers }
                        .map { it.id }
                        .toSet()
                }

                LocationLevel.SHELF -> {
                    tree.rooms
                        .flatMap { it.shelves }
                        .firstOrNull { it.id == state.id }
                        ?.tiers
                        .orEmpty()
                        .map { it.id }
                        .toSet()
                }

                LocationLevel.TIER -> {
                    setOf(state.id)
                }
            }
        AlertDialog(
            onDismissRequest = onClearState,
            title = { Text(stringResource(R.string.location_in_use_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pluralStringResource(R.plurals.location_in_use_message, state.copyCount, state.copyCount))
                    FilterChip(
                        selected = replacementTierId == null,
                        onClick = { replacementTierId = null },
                        label = { Text(stringResource(R.string.location_unset_action)) },
                    )
                    tree.tiers.filterNot { it.id in excludedTierIds }.forEach { tier ->
                        FilterChip(
                            selected = replacementTierId == tier.id,
                            onClick = { replacementTierId = tier.id },
                            label = { Text(tree.pathForTier(tier.id).orEmpty()) },
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onDelete(state.level, state.id, replacementTierId, replacementTierId == null)
                }) { Text(stringResource(R.string.location_move_delete)) }
            },
            dismissButton = { TextButton(onClick = onClearState) { Text(stringResource(R.string.location_cancel)) } },
        )
    }
}

@Composable
private fun LocationItemRow(
    name: String,
    detail: String,
    enabled: Boolean,
    onEdit: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 名称と内訳は1ノードにまとめ、操作ボタンは対象名を含むラベルで区別する。
        Column(
            modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
        ) {
            Text(name, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onClick = onUp, enabled = enabled) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = stringResource(R.string.location_move_up_target, name),
            )
        }
        IconButton(onClick = onDown, enabled = enabled) {
            Icon(
                Icons.Rounded.ArrowDownward,
                contentDescription = stringResource(R.string.location_move_down_target, name),
            )
        }
        IconButton(onClick = onEdit, enabled = enabled) {
            Icon(
                Icons.Rounded.Edit,
                contentDescription = stringResource(R.string.location_rename_target, name),
            )
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.location_delete_target, name),
            )
        }
    }
}

@Composable
private fun LocationNameDialog(
    target: LocationEditorTarget,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(target) { mutableStateOf(target.initialName) }
    val levelName =
        when (target.level) {
            LocationLevel.ROOM -> stringResource(R.string.location_level_room)
            LocationLevel.SHELF -> stringResource(R.string.location_level_shelf)
            LocationLevel.TIER -> stringResource(R.string.location_level_tier)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (target.id == null) R.string.location_add_title else R.string.location_rename_title, levelName))
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.location_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.location_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.location_cancel)) } },
    )
}

@Composable
private fun LibraryHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_help_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.library_help_ndc))
                Text(stringResource(R.string.library_help_location))
                Text(stringResource(R.string.library_help_reading_status))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.help_close)) }
        },
    )
}
