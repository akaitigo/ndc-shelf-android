package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.AiLibrarianAnswerUi
import dev.ndcshelf.app.AiLibrarianPhase
import dev.ndcshelf.app.AiLibrarianScopeOption
import dev.ndcshelf.app.AiLibrarianUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.ai.AiLibrarianAnswerEntry
import dev.ndcshelf.app.domain.ai.AiLibrarianFailure
import dev.ndcshelf.app.domain.ai.AiLibrarianField
import dev.ndcshelf.app.domain.ai.AiLibrarianHistoryEntry
import dev.ndcshelf.app.domain.ai.AiLibrarianIntent
import dev.ndcshelf.app.domain.ai.AiLibrarianLimits
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderId
import dev.ndcshelf.app.domain.ai.AiLibrarianReason
import dev.ndcshelf.app.domain.ai.AiLibrarianRequestDraft
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.model.LibraryBook
import java.text.DateFormat
import java.util.Date

/**
 * オプトインAI司書の相談画面。
 *
 * 既定OFFで、同意→質問→対象選択→送信内容プレビュー→明示確認→回答の順にしか
 * 進めない。回答には必ず参照した本と「推測であり蔵書データを変更しない」注記を添える。
 */
@Composable
fun AiLibrarianScreen(
    state: AiLibrarianUiState,
    onBack: () -> Unit,
    onQuestionChange: (String) -> Unit,
    onSelectScope: (AiLibrarianScopeOption) -> Unit,
    onToggleBook: (String) -> Unit,
    onClearBookSelection: () -> Unit,
    onSelectTag: (String?) -> Unit,
    onToggleField: (AiLibrarianField) -> Unit,
    onResetFields: () -> Unit,
    onPreview: () -> Unit,
    onDismissPreview: () -> Unit,
    onConfirmAsk: () -> Unit,
    onCancelAsk: () -> Unit,
    onDismissAnswer: () -> Unit,
    onGrantConsent: () -> Unit,
    onRevokeConsent: () -> Unit,
    onClearHistory: () -> Unit,
    contentPadding: PaddingValues,
) {
    var showConsentDialog by rememberSaveable { mutableStateOf(false) }

    if (showConsentDialog) {
        ConsentPayloadDialog(
            purpose = ConsentPurpose.AI_LIBRARIAN,
            payloadItems = state.targetBooks.map(LibraryBook::title),
            onAccept = {
                showConsentDialog = false
                onGrantConsent()
            },
            onDismiss = { showConsentDialog = false },
        )
    }

    state.pendingDraft?.let { draft ->
        AiLibrarianPreviewDialog(
            draft = draft,
            destinationLabel = stringResource(state.providerId.labelRes),
            onConfirm = onConfirmAsk,
            onDismiss = onDismissPreview,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.ai_librarian_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.ai_librarian_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.ai_librarian_provider_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item { AiLibrarianExamplesCard() }

        if (!state.consentGranted) {
            item {
                AiLibrarianConsentCard(onRequestGrant = { showConsentDialog = true })
            }
        } else {
            item {
                AiLibrarianQuestionCard(
                    state = state,
                    onQuestionChange = onQuestionChange,
                    onPreview = onPreview,
                )
            }
            item {
                AiLibrarianScopeCard(
                    state = state,
                    onSelectScope = onSelectScope,
                    onToggleBook = onToggleBook,
                    onClearBookSelection = onClearBookSelection,
                    onSelectTag = onSelectTag,
                )
            }
            item {
                AiLibrarianFieldsCard(
                    state = state,
                    onToggleField = onToggleField,
                    onResetFields = onResetFields,
                )
            }
        }

        state.failure?.let { failure ->
            item { AiLibrarianFailureCard(failure) }
        }

        if (state.phase == AiLibrarianPhase.ASKING) {
            item { AiLibrarianAskingCard(onCancelAsk) }
        }

        state.answer?.let { answer ->
            item { AiLibrarianAnswerCard(answer = answer, onDismiss = onDismissAnswer) }
        }

        item {
            AiLibrarianHistoryCard(
                history = state.history,
                onClearHistory = onClearHistory,
            )
        }

        if (state.consentGranted) {
            item {
                OutlinedButton(
                    onClick = onRevokeConsent,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                ) { Text(stringResource(R.string.ai_librarian_consent_revoke)) }
            }
        }

        item {
            TextButton(
                onClick = onBack,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text(stringResource(R.string.ai_librarian_back)) }
        }
    }
}

@Composable
private fun AiLibrarianExamplesCard() {
    AiLibrarianCard(titleRes = R.string.ai_librarian_examples_title) {
        Text("・" + stringResource(R.string.ai_librarian_example_pick_next))
        Text("・" + stringResource(R.string.ai_librarian_example_organize))
    }
}

@Composable
private fun AiLibrarianConsentCard(onRequestGrant: () -> Unit) {
    AiLibrarianCard(titleRes = R.string.ai_librarian_consent_title) {
        Text(
            text = stringResource(R.string.ai_librarian_consent_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRequestGrant, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ai_librarian_consent_grant))
        }
    }
}

@Composable
private fun AiLibrarianQuestionCard(
    state: AiLibrarianUiState,
    onQuestionChange: (String) -> Unit,
    onPreview: () -> Unit,
) {
    AiLibrarianCard(titleRes = R.string.ai_librarian_question_label) {
        OutlinedTextField(
            value = state.question,
            onValueChange = onQuestionChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.ai_librarian_question_placeholder)) },
        )
        Text(
            text =
                stringResource(
                    R.string.ai_librarian_question_counter,
                    state.question.length,
                    AiLibrarianLimits.MAX_QUESTION_LENGTH,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                stringResource(
                    R.string.ai_librarian_quota,
                    state.remainingQuestionsToday,
                    AiLibrarianLimits.MAX_QUESTIONS_PER_DAY,
                ),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = onPreview,
            enabled = state.phase != AiLibrarianPhase.ASKING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.ai_librarian_preview_button)) }
    }
}

@Composable
private fun AiLibrarianScopeCard(
    state: AiLibrarianUiState,
    onSelectScope: (AiLibrarianScopeOption) -> Unit,
    onToggleBook: (String) -> Unit,
    onClearBookSelection: () -> Unit,
    onSelectTag: (String?) -> Unit,
) {
    AiLibrarianCard(titleRes = R.string.ai_librarian_scope_title) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiLibrarianScopeOption.entries.forEach { option ->
                FilterChip(
                    selected = state.scope == option,
                    onClick = { onSelectScope(option) },
                    label = { Text(stringResource(option.labelRes)) },
                )
            }
        }
        val targetCount = state.targetBooks.size
        Text(stringResource(R.string.ai_librarian_target_count, targetCount))
        if (targetCount == 0) {
            Text(
                text = stringResource(R.string.ai_librarian_target_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text =
                stringResource(
                    R.string.ai_librarian_item_limit_notice,
                    AiLibrarianLimits.MAX_ITEMS_PER_REQUEST,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (state.scope) {
            AiLibrarianScopeOption.SELECTED_BOOKS -> {
                if (state.selectedCopyIds.isNotEmpty()) {
                    TextButton(onClick = onClearBookSelection) {
                        Text(stringResource(R.string.ai_librarian_scope_clear_selection))
                    }
                }
                state.libraryBooks.take(MAX_SELECTABLE_BOOKS).forEach { book ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = book.copyId in state.selectedCopyIds,
                            onCheckedChange = { onToggleBook(book.copyId) },
                        )
                        Text(book.title, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            AiLibrarianScopeOption.TAG -> {
                if (state.tags.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ai_librarian_tag_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.tags.take(MAX_TAG_CHIPS).forEach { entry ->
                            FilterChip(
                                selected = state.selectedTagId == entry.tag.id,
                                onClick = {
                                    onSelectTag(entry.tag.id.takeIf { it != state.selectedTagId })
                                },
                                label = { Text(entry.tag.name) },
                            )
                        }
                    }
                }
            }

            AiLibrarianScopeOption.SEARCH_RESULT -> {
                Unit
            }
        }
    }
}

@Composable
private fun AiLibrarianFieldsCard(
    state: AiLibrarianUiState,
    onToggleField: (AiLibrarianField) -> Unit,
    onResetFields: () -> Unit,
) {
    AiLibrarianCard(titleRes = R.string.ai_librarian_fields_title) {
        Text(
            text = stringResource(R.string.ai_librarian_fields_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AiLibrarianField.entries.forEach { field ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = field.required || field in state.includedFields,
                    enabled = !field.required,
                    onCheckedChange = { onToggleField(field) },
                )
                Column {
                    Text(stringResource(field.labelRes), style = MaterialTheme.typography.bodyMedium)
                    val noteRes =
                        when {
                            field.required -> R.string.ai_librarian_field_required
                            !field.includedByDefault -> R.string.ai_librarian_field_default_excluded
                            else -> null
                        }
                    noteRes?.let { res ->
                        Text(
                            text = stringResource(res),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        TextButton(onClick = onResetFields) {
            Text(stringResource(R.string.ai_librarian_fields_reset))
        }
    }
}

@Composable
private fun AiLibrarianAskingCard(onCancelAsk: () -> Unit) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp).clearAndSetSemantics {})
            Text(
                text = stringResource(R.string.ai_librarian_asking),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancelAsk) {
                Text(stringResource(R.string.ai_librarian_cancel_button))
            }
        }
    }
}

@Composable
private fun AiLibrarianFailureCard(failure: AiLibrarianFailure) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        Text(
            text = failure.message(),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AiLibrarianAnswerCard(
    answer: AiLibrarianAnswerUi,
    onDismiss: () -> Unit,
) {
    AiLibrarianCard(titleRes = R.string.ai_librarian_answer_title) {
        Text(
            text = stringResource(answer.answer.intent.headlineRes),
            style = MaterialTheme.typography.bodyMedium,
        )
        answer.answer.entries.forEach { entry ->
            AiLibrarianAnswerEntryBlock(entry = entry, titles = answer.titlesFor(entry.refs))
        }
        val referencedTitles = answer.referencedTitles
        Text(
            text = stringResource(R.string.ai_librarian_answer_references, referencedTitles.size),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        SelectionContainer {
            Column {
                referencedTitles.forEach { title ->
                    Text("・$title", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(
            text = stringResource(R.string.ai_librarian_answer_uncertainty),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.ai_librarian_answer_close))
        }
    }
}

@Composable
private fun AiLibrarianAnswerEntryBlock(
    entry: AiLibrarianAnswerEntry,
    titles: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = entry.label ?: stringResource(R.string.ai_librarian_answer_unclassified),
            style = MaterialTheme.typography.titleSmall,
        )
        titles.forEach { title ->
            Text("・$title", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = stringResource(entry.reason.messageRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AiLibrarianHistoryCard(
    history: List<AiLibrarianHistoryEntry>,
    onClearHistory: () -> Unit,
) {
    AiLibrarianCard(titleRes = R.string.ai_librarian_history_title) {
        if (history.isEmpty()) {
            Text(
                text = stringResource(R.string.ai_librarian_history_empty),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            history.forEach { entry ->
                Column {
                    Text(entry.question, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text =
                            stringResource(
                                R.string.ai_librarian_history_entry,
                                formatHistoryDate(entry.askedAtMillis),
                                entry.itemCount,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        OutlinedButton(
            onClick = onClearHistory,
            enabled = history.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.ai_librarian_history_clear)) }
    }
}

/**
 * 実行直前の最終確認。対象本の一覧・送信先・送信項目・除外項目を提示し、
 * 明示的に「この内容で質問する」を押した場合だけ送信する。
 */
@Composable
private fun AiLibrarianPreviewDialog(
    draft: AiLibrarianRequestDraft,
    destinationLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val excluded = draft.excludedFields
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_librarian_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ai_librarian_preview_description))
                Text(stringResource(R.string.ai_librarian_preview_question, draft.request.question))
                Text(stringResource(R.string.ai_librarian_preview_destination, destinationLabel))
                Text(
                    text =
                        stringResource(
                            R.string.ai_librarian_preview_books,
                            draft.references.size,
                        ),
                    fontWeight = FontWeight.Bold,
                )
                draft.references.take(MAX_PREVIEW_BOOKS).forEach { reference ->
                    Text("・${reference.title}", style = MaterialTheme.typography.bodySmall)
                }
                if (draft.references.size > MAX_PREVIEW_BOOKS) {
                    Text(
                        stringResource(
                            R.string.ai_librarian_preview_more,
                            draft.references.size - MAX_PREVIEW_BOOKS,
                        ),
                    )
                }
                Text(
                    stringResource(
                        R.string.ai_librarian_preview_included,
                        fieldLabels(draft.includedFields),
                    ),
                )
                Text(
                    stringResource(
                        R.string.ai_librarian_preview_excluded,
                        if (excluded.isEmpty()) {
                            stringResource(R.string.ai_librarian_preview_excluded_none)
                        } else {
                            fieldLabels(excluded)
                        },
                    ),
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.ai_librarian_preview_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.ai_librarian_preview_cancel))
            }
        },
    )
}

@Composable
private fun AiLibrarianCard(
    titleRes: Int,
    content: @Composable () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

@Composable
private fun AiLibrarianFailure.message(): String =
    when (this) {
        AiLibrarianFailure.NOT_CONSENTED -> {
            stringResource(R.string.ai_librarian_failure_not_consented)
        }

        AiLibrarianFailure.QUESTION_EMPTY -> {
            stringResource(R.string.ai_librarian_failure_question_empty)
        }

        AiLibrarianFailure.QUESTION_TOO_LONG -> {
            stringResource(
                R.string.ai_librarian_failure_question_too_long,
                AiLibrarianLimits.MAX_QUESTION_LENGTH,
            )
        }

        AiLibrarianFailure.NO_BOOKS_SELECTED -> {
            stringResource(R.string.ai_librarian_failure_no_books)
        }

        AiLibrarianFailure.ITEM_LIMIT_EXCEEDED -> {
            stringResource(
                R.string.ai_librarian_failure_item_limit,
                AiLibrarianLimits.MAX_ITEMS_PER_REQUEST,
            )
        }

        AiLibrarianFailure.DAILY_LIMIT_REACHED -> {
            stringResource(
                R.string.ai_librarian_failure_daily_limit,
                AiLibrarianLimits.MAX_QUESTIONS_PER_DAY,
            )
        }

        AiLibrarianFailure.TIMEOUT -> {
            stringResource(R.string.ai_librarian_failure_timeout)
        }

        AiLibrarianFailure.CANCELLED -> {
            stringResource(R.string.ai_librarian_failure_cancelled)
        }

        AiLibrarianFailure.PROVIDER_UNAVAILABLE -> {
            stringResource(R.string.ai_librarian_failure_provider_unavailable)
        }

        AiLibrarianFailure.PROVIDER_RATE_LIMITED -> {
            stringResource(R.string.ai_librarian_failure_provider_rate_limited)
        }

        AiLibrarianFailure.PROVIDER_ERROR -> {
            stringResource(R.string.ai_librarian_failure_provider_error)
        }
    }

/** 項目名を読点で連結する。joinToStringはinlineでないため明示ループで組み立てる。 */
@Composable
private fun fieldLabels(fields: List<AiLibrarianField>): String =
    buildString {
        fields.forEachIndexed { index, field ->
            if (index > 0) append("、")
            append(stringResource(field.labelRes))
        }
    }

internal val AiLibrarianField.labelRes: Int
    get() =
        when (this) {
            AiLibrarianField.TITLE -> R.string.ai_librarian_field_title
            AiLibrarianField.AUTHOR -> R.string.ai_librarian_field_author
            AiLibrarianField.PUBLISHER -> R.string.ai_librarian_field_publisher
            AiLibrarianField.PUBLISHED_YEAR -> R.string.ai_librarian_field_published_year
            AiLibrarianField.NDC -> R.string.ai_librarian_field_ndc
            AiLibrarianField.TAGS -> R.string.ai_librarian_field_tags
            AiLibrarianField.LOCATION -> R.string.ai_librarian_field_location
            AiLibrarianField.READING_STATUS -> R.string.ai_librarian_field_reading_status
            AiLibrarianField.NOTE -> R.string.ai_librarian_field_note
        }

internal val AiLibrarianScopeOption.labelRes: Int
    get() =
        when (this) {
            AiLibrarianScopeOption.SEARCH_RESULT -> R.string.ai_librarian_scope_search_result
            AiLibrarianScopeOption.SELECTED_BOOKS -> R.string.ai_librarian_scope_selected
            AiLibrarianScopeOption.TAG -> R.string.ai_librarian_scope_tag
        }

internal val AiLibrarianProviderId.labelRes: Int
    get() =
        when (this) {
            AiLibrarianProviderId.ON_DEVICE_HEURISTIC -> R.string.ai_librarian_destination_on_device
            AiLibrarianProviderId.ON_DEVICE_LLM -> R.string.ai_librarian_destination_on_device_llm
        }

internal val AiLibrarianIntent.headlineRes: Int
    get() =
        when (this) {
            AiLibrarianIntent.PICK_NEXT -> R.string.ai_librarian_intent_pick_next
            AiLibrarianIntent.ORGANIZE -> R.string.ai_librarian_intent_organize
            AiLibrarianIntent.OVERVIEW -> R.string.ai_librarian_intent_overview
        }

internal val AiLibrarianReason.messageRes: Int
    get() =
        when (this) {
            AiLibrarianReason.UNREAD_FIRST -> R.string.ai_librarian_reason_unread_first
            AiLibrarianReason.CATEGORY_MATCH -> R.string.ai_librarian_reason_category_match
            AiLibrarianReason.BIBLIOGRAPHIC_ORDER -> R.string.ai_librarian_reason_bibliographic_order
            AiLibrarianReason.CATEGORY_GROUP -> R.string.ai_librarian_reason_category_group
            AiLibrarianReason.UNCLASSIFIED_GROUP -> R.string.ai_librarian_reason_unclassified_group
            AiLibrarianReason.LIBRARY_OVERVIEW -> R.string.ai_librarian_reason_library_overview
        }

private fun formatHistoryDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private const val MAX_PREVIEW_BOOKS = 10
private const val MAX_SELECTABLE_BOOKS = 100
private const val MAX_TAG_CHIPS = 10
