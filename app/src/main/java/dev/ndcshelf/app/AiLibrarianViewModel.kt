package dev.ndcshelf.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.ndcshelf.app.domain.ai.AiLibrarianAnswer
import dev.ndcshelf.app.domain.ai.AiLibrarianBookReference
import dev.ndcshelf.app.domain.ai.AiLibrarianDayKey
import dev.ndcshelf.app.domain.ai.AiLibrarianFailure
import dev.ndcshelf.app.domain.ai.AiLibrarianField
import dev.ndcshelf.app.domain.ai.AiLibrarianHistoryEntry
import dev.ndcshelf.app.domain.ai.AiLibrarianHistoryStore
import dev.ndcshelf.app.domain.ai.AiLibrarianLimits
import dev.ndcshelf.app.domain.ai.AiLibrarianProvider
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderErrorKind
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderException
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderId
import dev.ndcshelf.app.domain.ai.AiLibrarianRequestBuilder
import dev.ndcshelf.app.domain.ai.AiLibrarianRequestDraft
import dev.ndcshelf.app.domain.ai.AiLibrarianRequestResult
import dev.ndcshelf.app.domain.ai.AiLibrarianUsageStore
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRepository
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.TagAssignment
import dev.ndcshelf.app.domain.model.TagWithUsage
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.ReadingHistoryRepository
import dev.ndcshelf.app.domain.repository.TagRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.TimeZone
import java.util.UUID

/**
 * オプトインAI司書のViewModel（設計判断はdocs/adr/0007-optin-ai-librarian.md）。
 *
 * 実行の不変条件:
 * - [ConsentPurpose.AI_LIBRARIAN]へ同意していなければ、プロバイダを一度も呼ばない。
 * - [preparePreview]で作った下書きを利用者が確認しない限り[confirmAsk]は送信しない。
 * - プロバイダへ渡すのは[AiLibrarianRequestDraft.request]だけで、リポジトリへの
 *   書き込みは一切行わない（回答で蔵書データを変更しない）。
 */
class AiLibrarianViewModel(
    libraryRepository: LibraryRepository,
    tagRepository: TagRepository? = null,
    readingHistoryRepository: ReadingHistoryRepository? = null,
    private val consentRepository: ConsentRepository? = null,
    private val provider: AiLibrarianProvider,
    private val usageStore: AiLibrarianUsageStore,
    private val historyStore: AiLibrarianHistoryStore,
    private val nowMillisProvider: () -> Long = System::currentTimeMillis,
    private val timeZoneProvider: () -> TimeZone = TimeZone::getDefault,
    private val requestTimeoutMillis: Long = AiLibrarianLimits.REQUEST_TIMEOUT_MILLIS,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            AiLibrarianUiState(
                providerId = provider.id,
                providerSendsDataOffDevice = provider.sendsDataOffDevice,
                history = historyStore.load(),
                remainingQuestionsToday = remainingQuestionsToday(),
            ),
        )
    val state: StateFlow<AiLibrarianUiState> = _state.asStateFlow()

    private var askJob: Job? = null

    init {
        viewModelScope.launch {
            libraryRepository.observeLibrary().collect { books ->
                _state.update { current -> current.copy(libraryBooks = books) }
            }
        }
        viewModelScope.launch {
            (tagRepository?.observeTags() ?: flowOf(emptyList())).collect { tags ->
                _state.update { current -> current.copy(tags = tags) }
            }
        }
        viewModelScope.launch {
            (tagRepository?.observeAssignments() ?: flowOf(emptyList())).collect { assignments ->
                val byWork =
                    assignments
                        .groupBy(TagAssignment::workId)
                        .mapValues { (_, values) -> values.mapTo(hashSetOf(), TagAssignment::tagId) }
                _state.update { current -> current.copy(tagIdsByWork = byWork) }
            }
        }
        viewModelScope.launch {
            (readingHistoryRepository?.observeAllSessions() ?: flowOf(emptyList())).collect { sessions ->
                val notes =
                    sessions
                        .filter { session -> !session.note.isNullOrBlank() }
                        .sortedBy { session -> session.updatedAt }
                        .associate { session -> session.copyId to session.note.orEmpty() }
                _state.update { current -> current.copy(notesByCopyId = notes) }
            }
        }
        viewModelScope.launch {
            (consentRepository?.observeConsents() ?: flowOf(emptyMap())).collect { consents ->
                val granted = consents[ConsentPurpose.AI_LIBRARIAN]?.granted == true
                _state.update { current ->
                    if (granted) {
                        current.copy(consentGranted = true)
                    } else {
                        // 撤回は即時に反映し、確認待ちの下書きと表示中の回答も破棄する。
                        current.copy(
                            consentGranted = false,
                            pendingDraft = null,
                            answer = null,
                            phase = AiLibrarianPhase.EDITING,
                        )
                    }
                }
            }
        }
    }

    /** 本棚の現在の検索結果を対象範囲として渡す。route引数へは個人データを載せない。 */
    fun setSearchResultCopyIds(copyIds: Set<String>) {
        _state.update { current ->
            if (current.searchResultCopyIds == copyIds) current else current.copy(searchResultCopyIds = copyIds)
        }
    }

    fun updateQuestion(question: String) {
        _state.update { current ->
            current.copy(
                question = question.take(AiLibrarianLimits.MAX_QUESTION_LENGTH),
                pendingDraft = null,
                failure = null,
            )
        }
    }

    fun selectScope(scope: AiLibrarianScopeOption) {
        _state.update { current -> current.copy(scope = scope, pendingDraft = null, failure = null) }
    }

    fun toggleBookSelection(copyId: String) {
        _state.update { current ->
            val next =
                if (copyId in current.selectedCopyIds) {
                    current.selectedCopyIds - copyId
                } else {
                    current.selectedCopyIds + copyId
                }
            current.copy(selectedCopyIds = next, pendingDraft = null, failure = null)
        }
    }

    fun clearBookSelection() {
        _state.update { current ->
            current.copy(selectedCopyIds = emptySet(), pendingDraft = null, failure = null)
        }
    }

    fun selectTag(tagId: String?) {
        _state.update { current ->
            current.copy(selectedTagId = tagId, pendingDraft = null, failure = null)
        }
    }

    /** 既定で除外している項目は、この明示操作でしか送信対象へ入らない。 */
    fun toggleField(field: AiLibrarianField) {
        if (field.required) return
        _state.update { current ->
            val next =
                if (field in current.includedFields) {
                    current.includedFields - field
                } else {
                    current.includedFields + field
                }
            current.copy(includedFields = next, pendingDraft = null, failure = null)
        }
    }

    fun resetFieldsToDefault() {
        _state.update { current ->
            current.copy(
                includedFields = AiLibrarianField.DEFAULT_INCLUDED,
                pendingDraft = null,
                failure = null,
            )
        }
    }

    /** 送信内容（対象本・送信先・送信項目）を組み立てて確認待ちにする。まだ送信しない。 */
    fun preparePreview() {
        val current = _state.value
        if (!current.consentGranted) {
            fail(AiLibrarianFailure.NOT_CONSENTED)
            return
        }
        if (remainingQuestionsToday() <= 0) {
            fail(AiLibrarianFailure.DAILY_LIMIT_REACHED)
            return
        }
        val result =
            AiLibrarianRequestBuilder.build(
                question = current.question,
                books = current.targetBooks,
                includedFields = current.includedFields,
                tagNamesByWorkId = current.tagNamesByWorkId,
                notesByCopyId = current.notesByCopyId,
            )
        when (result) {
            is AiLibrarianRequestResult.Rejected -> {
                fail(result.failure)
            }

            is AiLibrarianRequestResult.Prepared -> {
                _state.update { state ->
                    state.copy(
                        pendingDraft = result.draft,
                        phase = AiLibrarianPhase.PREVIEW,
                        failure = null,
                    )
                }
            }
        }
    }

    fun dismissPreview() {
        _state.update { current ->
            current.copy(pendingDraft = null, phase = AiLibrarianPhase.EDITING)
        }
    }

    /** 確認済みの下書きだけを送信する。下書きが無ければ何もしない（誤操作防止）。 */
    fun confirmAsk() {
        val current = _state.value
        val draft = current.pendingDraft ?: return
        if (!current.consentGranted) {
            fail(AiLibrarianFailure.NOT_CONSENTED)
            return
        }
        val dayKey = AiLibrarianDayKey.of(nowMillisProvider(), timeZoneProvider())
        if (usageStore.usedCount(dayKey) >= AiLibrarianLimits.MAX_QUESTIONS_PER_DAY) {
            fail(AiLibrarianFailure.DAILY_LIMIT_REACHED)
            return
        }
        askJob?.cancel()
        _state.update { state ->
            state.copy(
                pendingDraft = null,
                phase = AiLibrarianPhase.ASKING,
                failure = null,
                answer = null,
            )
        }
        usageStore.recordUse(dayKey)
        _state.update { state -> state.copy(remainingQuestionsToday = remainingQuestionsToday()) }
        askJob =
            viewModelScope.launch {
                if (consentRepository?.isGranted(ConsentPurpose.AI_LIBRARIAN) == false) {
                    fail(AiLibrarianFailure.NOT_CONSENTED)
                    return@launch
                }
                val answer =
                    try {
                        withTimeout(requestTimeoutMillis) { provider.answer(draft.request) }
                    } catch (_: TimeoutCancellationException) {
                        fail(AiLibrarianFailure.TIMEOUT)
                        return@launch
                    } catch (exception: AiLibrarianProviderException) {
                        fail(exception.kind.toFailure())
                        return@launch
                    } catch (cancellation: CancellationException) {
                        // キャンセルは呼び出し側（cancelAsk）が状態を確定させる。
                        throw cancellation
                    } catch (_: Exception) {
                        fail(AiLibrarianFailure.PROVIDER_ERROR)
                        return@launch
                    }
                onAnswered(draft, answer)
            }
    }

    fun cancelAsk() {
        if (_state.value.phase != AiLibrarianPhase.ASKING) return
        askJob?.cancel()
        askJob = null
        fail(AiLibrarianFailure.CANCELLED)
    }

    fun dismissAnswer() {
        _state.update { current -> current.copy(answer = null, phase = AiLibrarianPhase.EDITING) }
    }

    fun dismissFailure() {
        _state.update { current -> current.copy(failure = null) }
    }

    fun grantConsent() {
        val repository = consentRepository ?: return
        viewModelScope.launch {
            runCatching { repository.grant(ConsentPurpose.AI_LIBRARIAN) }
        }
    }

    fun revokeConsent() {
        val repository = consentRepository ?: return
        askJob?.cancel()
        askJob = null
        _state.update { current ->
            current.copy(
                consentGranted = false,
                pendingDraft = null,
                answer = null,
                phase = AiLibrarianPhase.EDITING,
            )
        }
        viewModelScope.launch {
            runCatching { repository.revoke(ConsentPurpose.AI_LIBRARIAN) }
        }
    }

    /** 質問履歴を端末内から全件削除する。 */
    fun clearHistory() {
        historyStore.clearHistory()
        _state.update { current -> current.copy(history = emptyList()) }
    }

    private fun onAnswered(
        draft: AiLibrarianRequestDraft,
        answer: AiLibrarianAnswer,
    ) {
        val referenced = draft.references.filter { reference -> reference.ref in answer.referencedRefs }
        val entry =
            AiLibrarianHistoryEntry(
                id = UUID.randomUUID().toString(),
                askedAtMillis = nowMillisProvider(),
                question = draft.request.question,
                intent = answer.intent,
                itemCount = draft.request.items.size,
                includedFields = draft.request.includedFields,
                referencedTitles = referenced.map(AiLibrarianBookReference::title),
            )
        val history = historyStore.append(entry)
        _state.update { current ->
            current.copy(
                phase = AiLibrarianPhase.ANSWERED,
                answer =
                    AiLibrarianAnswerUi(
                        answer = answer,
                        references = draft.references,
                        itemCount = draft.request.items.size,
                        includedFields = draft.request.includedFields,
                    ),
                history = history,
                failure = null,
                remainingQuestionsToday = remainingQuestionsToday(),
            )
        }
    }

    private fun fail(failure: AiLibrarianFailure) {
        _state.update { current ->
            current.copy(
                failure = failure,
                phase = AiLibrarianPhase.EDITING,
                pendingDraft = null,
                remainingQuestionsToday = remainingQuestionsToday(),
            )
        }
    }

    private fun remainingQuestionsToday(): Int {
        val dayKey = AiLibrarianDayKey.of(nowMillisProvider(), timeZoneProvider())
        val used = usageStore.usedCount(dayKey)
        return (AiLibrarianLimits.MAX_QUESTIONS_PER_DAY - used).coerceAtLeast(0)
    }

    private fun AiLibrarianProviderErrorKind.toFailure(): AiLibrarianFailure =
        when (this) {
            AiLibrarianProviderErrorKind.UNAVAILABLE -> AiLibrarianFailure.PROVIDER_UNAVAILABLE
            AiLibrarianProviderErrorKind.RATE_LIMITED -> AiLibrarianFailure.PROVIDER_RATE_LIMITED
            AiLibrarianProviderErrorKind.INVALID_RESPONSE -> AiLibrarianFailure.PROVIDER_ERROR
            AiLibrarianProviderErrorKind.TRANSPORT -> AiLibrarianFailure.PROVIDER_ERROR
        }

    companion object {
        fun factory(
            libraryRepository: LibraryRepository,
            tagRepository: TagRepository?,
            readingHistoryRepository: ReadingHistoryRepository?,
            consentRepository: ConsentRepository?,
            provider: AiLibrarianProvider,
            usageStore: AiLibrarianUsageStore,
            historyStore: AiLibrarianHistoryStore,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AiLibrarianViewModel(
                        libraryRepository = libraryRepository,
                        tagRepository = tagRepository,
                        readingHistoryRepository = readingHistoryRepository,
                        consentRepository = consentRepository,
                        provider = provider,
                        usageStore = usageStore,
                        historyStore = historyStore,
                    )
                }
            }
    }
}

/** 対象範囲の選び方。 */
enum class AiLibrarianScopeOption {
    /** 本棚の現在の検索結果。 */
    SEARCH_RESULT,

    /** 一覧から選んだ本。 */
    SELECTED_BOOKS,

    /** 選んだタグの付いた本。 */
    TAG,
}

enum class AiLibrarianPhase {
    EDITING,
    PREVIEW,
    ASKING,
    ANSWERED,
}

data class AiLibrarianAnswerUi(
    val answer: AiLibrarianAnswer,
    val references: List<AiLibrarianBookReference>,
    val itemCount: Int,
    val includedFields: List<AiLibrarianField>,
) {
    private val titlesByRef: Map<String, String> =
        references.associate { reference -> reference.ref to reference.title }

    fun titlesFor(refs: List<String>): List<String> = refs.mapNotNull(titlesByRef::get)

    /** 回答が参照した本の冊名。UIは必ずこの一覧を回答と一緒に表示する。 */
    val referencedTitles: List<String>
        get() = titlesFor(answer.referencedRefs)
}

data class AiLibrarianUiState(
    val consentGranted: Boolean = false,
    val providerId: AiLibrarianProviderId = AiLibrarianProviderId.ON_DEVICE_HEURISTIC,
    val providerSendsDataOffDevice: Boolean = false,
    val question: String = "",
    val scope: AiLibrarianScopeOption = AiLibrarianScopeOption.SEARCH_RESULT,
    val includedFields: Set<AiLibrarianField> = AiLibrarianField.DEFAULT_INCLUDED,
    val selectedCopyIds: Set<String> = emptySet(),
    val selectedTagId: String? = null,
    val libraryBooks: List<LibraryBook> = emptyList(),
    val searchResultCopyIds: Set<String> = emptySet(),
    val tags: List<TagWithUsage> = emptyList(),
    val tagIdsByWork: Map<String, Set<String>> = emptyMap(),
    val notesByCopyId: Map<String, String> = emptyMap(),
    val pendingDraft: AiLibrarianRequestDraft? = null,
    val phase: AiLibrarianPhase = AiLibrarianPhase.EDITING,
    val answer: AiLibrarianAnswerUi? = null,
    val failure: AiLibrarianFailure? = null,
    val remainingQuestionsToday: Int = AiLibrarianLimits.MAX_QUESTIONS_PER_DAY,
    val history: List<AiLibrarianHistoryEntry> = emptyList(),
) {
    /** 現在の範囲指定で送信対象になる本。上限超過は送信前の組み立てで拒否する。 */
    val targetBooks: List<LibraryBook>
        get() =
            when (scope) {
                AiLibrarianScopeOption.SEARCH_RESULT -> {
                    libraryBooks.filter { book -> book.copyId in searchResultCopyIds }
                }

                AiLibrarianScopeOption.SELECTED_BOOKS -> {
                    libraryBooks.filter { book -> book.copyId in selectedCopyIds }
                }

                AiLibrarianScopeOption.TAG -> {
                    val tagId = selectedTagId
                    if (tagId == null) {
                        emptyList()
                    } else {
                        libraryBooks.filter { book -> tagIdsByWork[book.workId]?.contains(tagId) == true }
                    }
                }
            }

    /** 対象本のタグ名。TAGS項目を明示選択した場合だけペイロードへ入る。 */
    val tagNamesByWorkId: Map<String, List<String>>
        get() {
            val namesById = tags.associate { entry -> entry.tag.id to entry.tag.name }
            return tagIdsByWork.mapValues { (_, ids) -> ids.mapNotNull(namesById::get).sorted() }
        }

    val excludedFields: List<AiLibrarianField>
        get() = AiLibrarianField.entries.filterNot { field -> field in includedFields }
}
