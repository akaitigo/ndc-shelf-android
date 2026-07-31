package dev.ndcshelf.app

import dev.ndcshelf.app.domain.ai.AI_LIBRARIAN_SYSTEM_INSTRUCTION
import dev.ndcshelf.app.domain.ai.AiLibrarianAnswer
import dev.ndcshelf.app.domain.ai.AiLibrarianAnswerEntry
import dev.ndcshelf.app.domain.ai.AiLibrarianFailure
import dev.ndcshelf.app.domain.ai.AiLibrarianField
import dev.ndcshelf.app.domain.ai.AiLibrarianIntent
import dev.ndcshelf.app.domain.ai.AiLibrarianLimits
import dev.ndcshelf.app.domain.ai.AiLibrarianProvider
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderErrorKind
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderException
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderId
import dev.ndcshelf.app.domain.ai.AiLibrarianReason
import dev.ndcshelf.app.domain.ai.AiLibrarianRequest
import dev.ndcshelf.app.domain.ai.InMemoryAiLibrarianHistoryStore
import dev.ndcshelf.app.domain.ai.InMemoryAiLibrarianUsageStore
import dev.ndcshelf.app.domain.ai.OnDeviceHeuristicLibrarian
import dev.ndcshelf.app.domain.ai.aiTestBook
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRecord
import dev.ndcshelf.app.domain.consent.ConsentRepository
import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiLibrarianViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun withoutConsentTheProviderIsNeverCalled() =
        runTest(dispatcher) {
            val provider = RecordingProvider()
            val viewModel = viewModel(provider = provider)
            viewModel.updateQuestion("次に読む本を選んで")
            selectAllBooks(viewModel)

            viewModel.preparePreview()
            viewModel.confirmAsk()

            assertEquals(AiLibrarianFailure.NOT_CONSENTED, viewModel.state.value.failure)
            assertTrue(provider.requests.isEmpty())
            assertNull(viewModel.state.value.pendingDraft)
        }

    @Test
    fun sendingRequiresAnExplicitlyConfirmedPreview() =
        runTest(dispatcher) {
            val provider = RecordingProvider()
            val viewModel = viewModel(provider = provider, consented = true)
            viewModel.updateQuestion("次に読む本を選んで")
            selectAllBooks(viewModel)

            viewModel.confirmAsk()

            assertTrue("確認なしでは送信しない", provider.requests.isEmpty())

            viewModel.preparePreview()
            assertNotNull(viewModel.state.value.pendingDraft)
            assertTrue("プレビュー生成だけでは送信しない", provider.requests.isEmpty())

            viewModel.confirmAsk()
            advanceUntilIdle()

            assertEquals(1, provider.requests.size)
        }

    @Test
    fun dismissingThePreviewCancelsTheSend() =
        runTest(dispatcher) {
            val provider = RecordingProvider()
            val viewModel = viewModel(provider = provider, consented = true)
            viewModel.updateQuestion("次に読む本を選んで")
            selectAllBooks(viewModel)
            viewModel.preparePreview()

            viewModel.dismissPreview()
            viewModel.confirmAsk()
            advanceUntilIdle()

            assertTrue(provider.requests.isEmpty())
        }

    @Test
    fun payloadExcludesLocationReadingStatusAndNoteByDefault() =
        runTest(dispatcher) {
            val provider = RecordingProvider()
            val viewModel = viewModel(provider = provider, consented = true)
            viewModel.updateQuestion("次に読む本を選んで")
            selectAllBooks(viewModel)
            viewModel.preparePreview()
            viewModel.confirmAsk()
            advanceUntilIdle()

            val request = provider.requests.single()
            assertEquals(AI_LIBRARIAN_SYSTEM_INSTRUCTION, request.systemInstruction)
            request.items.forEach { item ->
                assertNull(item.location)
                assertNull(item.readingStatus)
                assertNull(item.note)
            }
            val payload = Json.encodeToString(request)
            assertFalse(payload.contains("サンプル書斎"))
            assertFalse(payload.contains(ReadingStatus.UNREAD.label))
        }

    @Test
    fun answeringNeverWritesToTheLibraryRepository() =
        runTest(dispatcher) {
            val repository = RecordingLibraryRepository(books())
            val viewModel =
                viewModel(
                    repository = repository,
                    provider = OnDeviceHeuristicLibrarian(),
                    consented = true,
                )
            viewModel.updateQuestion("次に読む本を選んで")
            selectAllBooks(viewModel)
            viewModel.preparePreview()
            viewModel.confirmAsk()
            advanceUntilIdle()

            assertNotNull(viewModel.state.value.answer)
            assertTrue("蔵書データを変更してはならない", repository.mutations.isEmpty())
        }

    @Test
    fun answerExposesReferencedTitlesForDisplay() =
        runTest(dispatcher) {
            val viewModel = viewModel(provider = OnDeviceHeuristicLibrarian(), consented = true)
            viewModel.updateQuestion("次に読む本を選んで")
            selectAllBooks(viewModel)
            viewModel.preparePreview()
            viewModel.confirmAsk()
            advanceUntilIdle()

            val answer = requireNotNull(viewModel.state.value.answer)
            assertTrue(answer.referencedTitles.isNotEmpty())
            assertTrue(answer.referencedTitles.all { title -> title.startsWith("匿名サンプル図書") })
        }

    @Test
    fun explicitlySelectedFieldsAreIncludedInThePayload() =
        runTest(dispatcher) {
            val provider = RecordingProvider()
            val viewModel = viewModel(provider = provider, consented = true)
            viewModel.updateQuestion("次に読む本を選んで")
            selectAllBooks(viewModel)
            viewModel.toggleField(AiLibrarianField.LOCATION)
            viewModel.preparePreview()
            viewModel.confirmAsk()
            advanceUntilIdle()

            assertTrue(
                provider.requests
                    .single()
                    .items
                    .all { item -> item.location != null },
            )
        }

    @Test
    fun requiredFieldsCannotBeToggledOff() =
        runTest(dispatcher) {
            val viewModel = viewModel(consented = true)

            viewModel.toggleField(AiLibrarianField.TITLE)

            assertTrue(AiLibrarianField.TITLE in viewModel.state.value.includedFields)
        }

    @Test
    fun dailyLimitBlocksFurtherQuestionsWithAClearFailure() =
        runTest(dispatcher) {
            val usage = InMemoryAiLibrarianUsageStore()
            repeat(AiLibrarianLimits.MAX_QUESTIONS_PER_DAY) { usage.recordUse(TODAY_KEY) }
            val provider = RecordingProvider()
            val viewModel = viewModel(provider = provider, consented = true, usageStore = usage)
            viewModel.updateQuestion("次に読む本を選んで")
            selectAllBooks(viewModel)

            viewModel.preparePreview()
            advanceUntilIdle()

            assertEquals(AiLibrarianFailure.DAILY_LIMIT_REACHED, viewModel.state.value.failure)
            assertEquals(0, viewModel.state.value.remainingQuestionsToday)
            assertTrue(provider.requests.isEmpty())
        }

    @Test
    fun tooManyTargetBooksAreRejectedBeforeSending() =
        runTest(dispatcher) {
            val many =
                (0..AiLibrarianLimits.MAX_ITEMS_PER_REQUEST).map { index ->
                    aiTestBook(copyId = "copy-$index", workId = "work-$index", title = "匿名サンプル図書$index")
                }
            val provider = RecordingProvider()
            val viewModel =
                viewModel(
                    repository = RecordingLibraryRepository(many),
                    provider = provider,
                    consented = true,
                )
            viewModel.updateQuestion("次に読む本を選んで")
            selectAllBooks(viewModel)

            viewModel.preparePreview()

            assertEquals(AiLibrarianFailure.ITEM_LIMIT_EXCEEDED, viewModel.state.value.failure)
            assertTrue(provider.requests.isEmpty())
        }

    @Test
    fun timeoutIsReportedWithoutChangingStoredData() =
        runTest(dispatcher) {
            val repository = RecordingLibraryRepository(books())
            val viewModel =
                viewModel(
                    repository = repository,
                    provider = SlowProvider(delayMillis = 60_000L),
                    consented = true,
                    timeoutMillis = 1_000L,
                )
            viewModel.updateQuestion("次に読む本を選んで")
            selectAllBooks(viewModel)
            viewModel.preparePreview()
            viewModel.confirmAsk()
            advanceUntilIdle()

            assertEquals(AiLibrarianFailure.TIMEOUT, viewModel.state.value.failure)
            assertEquals(AiLibrarianPhase.EDITING, viewModel.state.value.phase)
            assertNull(viewModel.state.value.answer)
            assertTrue(repository.mutations.isEmpty())
        }

    @Test
    fun cancellingAnInFlightQuestionStopsIt() =
        runTest(dispatcher) {
            val provider = HangingProvider()
            val viewModel = viewModel(provider = provider, consented = true)
            viewModel.updateQuestion("次に読む本を選んで")
            selectAllBooks(viewModel)
            viewModel.preparePreview()
            viewModel.confirmAsk()

            assertEquals(AiLibrarianPhase.ASKING, viewModel.state.value.phase)

            viewModel.cancelAsk()
            advanceUntilIdle()

            assertEquals(AiLibrarianFailure.CANCELLED, viewModel.state.value.failure)
            assertEquals(AiLibrarianPhase.EDITING, viewModel.state.value.phase)
            assertNull(viewModel.state.value.answer)
        }

    @Test
    fun providerOutageIsClassifiedForTheUser() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    provider = FailingProvider(AiLibrarianProviderErrorKind.UNAVAILABLE),
                    consented = true,
                )
            ask(viewModel)

            assertEquals(AiLibrarianFailure.PROVIDER_UNAVAILABLE, viewModel.state.value.failure)
        }

    @Test
    fun providerRateLimitIsClassifiedForTheUser() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    provider = FailingProvider(AiLibrarianProviderErrorKind.RATE_LIMITED),
                    consented = true,
                )
            ask(viewModel)

            assertEquals(AiLibrarianFailure.PROVIDER_RATE_LIMITED, viewModel.state.value.failure)
        }

    @Test
    fun unexpectedProviderErrorIsClassifiedForTheUser() =
        runTest(dispatcher) {
            val viewModel = viewModel(provider = ThrowingProvider(), consented = true)
            ask(viewModel)

            assertEquals(AiLibrarianFailure.PROVIDER_ERROR, viewModel.state.value.failure)
        }

    @Test
    fun revokingConsentImmediatelyDisablesTheFeature() =
        runTest(dispatcher) {
            val consents = FakeAiConsentRepository(granted = true)
            val provider = RecordingProvider()
            val viewModel = viewModel(provider = provider, consents = consents)
            viewModel.updateQuestion("次に読む本を選んで")
            selectAllBooks(viewModel)
            viewModel.preparePreview()
            assertNotNull(viewModel.state.value.pendingDraft)

            viewModel.revokeConsent()
            advanceUntilIdle()

            assertFalse(viewModel.state.value.consentGranted)
            assertNull("撤回時に確認待ちの下書きも破棄する", viewModel.state.value.pendingDraft)

            viewModel.confirmAsk()
            advanceUntilIdle()
            assertTrue(provider.requests.isEmpty())
        }

    @Test
    fun revokingConsentDropsTheDisplayedAnswer() =
        runTest(dispatcher) {
            val consents = FakeAiConsentRepository(granted = true)
            val viewModel = viewModel(provider = OnDeviceHeuristicLibrarian(), consents = consents)
            ask(viewModel)
            assertNotNull(viewModel.state.value.answer)

            viewModel.revokeConsent()
            advanceUntilIdle()

            assertNull(viewModel.state.value.answer)
        }

    @Test
    fun historyIsRecordedAndCanBeDeletedCompletely() =
        runTest(dispatcher) {
            val history = InMemoryAiLibrarianHistoryStore()
            val viewModel =
                viewModel(
                    provider = OnDeviceHeuristicLibrarian(),
                    consented = true,
                    historyStore = history,
                )
            ask(viewModel)

            assertEquals(1, viewModel.state.value.history.size)
            assertEquals(1, history.load().size)

            viewModel.clearHistory()

            assertTrue(
                viewModel.state.value.history
                    .isEmpty(),
            )
            assertTrue(history.load().isEmpty())
        }

    @Test
    fun libraryFeaturesKeepWorkingWhileAiIsDisabled() =
        runTest(dispatcher) {
            val repository = RecordingLibraryRepository(books())
            val consents = FakeAiConsentRepository(granted = false)
            val mainViewModel =
                MainViewModel(repository = repository, consentRepository = consents)
            val aiViewModel = viewModel(repository = repository, consents = consents)
            val booksCollection = backgroundScope.launch { mainViewModel.books.collect() }
            val searchCollection = backgroundScope.launch { mainViewModel.librarySearchResult.collect() }
            advanceUntilIdle()

            assertEquals(books().size, mainViewModel.books.value.size)
            mainViewModel.updateLibraryQuery("匿名")
            advanceUntilIdle()
            assertEquals(books().size, mainViewModel.librarySearchResult.value.books.size)
            assertFalse(aiViewModel.state.value.consentGranted)
            assertTrue(repository.mutations.isEmpty())
            booksCollection.cancel()
            searchCollection.cancel()
        }

    private fun ask(viewModel: AiLibrarianViewModel) {
        viewModel.updateQuestion("次に読む本を選んで")
        selectAllBooks(viewModel)
        viewModel.preparePreview()
        viewModel.confirmAsk()
    }

    private fun selectAllBooks(viewModel: AiLibrarianViewModel) {
        viewModel.selectScope(AiLibrarianScopeOption.SEARCH_RESULT)
        viewModel.setSearchResultCopyIds(
            viewModel.state.value.libraryBooks
                .mapTo(mutableSetOf()) { book -> book.copyId },
        )
    }

    private fun viewModel(
        repository: LibraryRepository = RecordingLibraryRepository(books()),
        provider: AiLibrarianProvider = RecordingProvider(),
        consented: Boolean = false,
        consents: FakeAiConsentRepository = FakeAiConsentRepository(consented),
        usageStore: InMemoryAiLibrarianUsageStore = InMemoryAiLibrarianUsageStore(),
        historyStore: InMemoryAiLibrarianHistoryStore = InMemoryAiLibrarianHistoryStore(),
        timeoutMillis: Long = AiLibrarianLimits.REQUEST_TIMEOUT_MILLIS,
    ): AiLibrarianViewModel =
        AiLibrarianViewModel(
            libraryRepository = repository,
            consentRepository = consents,
            provider = provider,
            usageStore = usageStore,
            historyStore = historyStore,
            nowMillisProvider = { FIXED_NOW },
            timeZoneProvider = { java.util.TimeZone.getTimeZone("UTC") },
            requestTimeoutMillis = timeoutMillis,
        )

    private companion object {
        const val FIXED_NOW = 1_753_000_000_000L
        val TODAY_KEY: String =
            dev.ndcshelf.app.domain.ai.AiLibrarianDayKey
                .of(FIXED_NOW, java.util.TimeZone.getTimeZone("UTC"))

        fun books(): List<LibraryBook> =
            listOf(
                aiTestBook(copyId = "copy-1", workId = "work-1", title = "匿名サンプル図書A"),
                aiTestBook(
                    copyId = "copy-2",
                    workId = "work-2",
                    title = "匿名サンプル図書B",
                    ndcCode = "913.6",
                ),
            )
    }
}

private class RecordingProvider : AiLibrarianProvider {
    val requests = mutableListOf<AiLibrarianRequest>()

    override val id: AiLibrarianProviderId = AiLibrarianProviderId.ON_DEVICE_HEURISTIC

    override val sendsDataOffDevice: Boolean = false

    override suspend fun answer(request: AiLibrarianRequest): AiLibrarianAnswer {
        requests += request
        return AiLibrarianAnswer(
            intent = AiLibrarianIntent.OVERVIEW,
            entries =
                listOf(
                    AiLibrarianAnswerEntry(
                        label = null,
                        reason = AiLibrarianReason.LIBRARY_OVERVIEW,
                        refs = request.items.map { item -> item.ref },
                    ),
                ),
        )
    }
}

private class SlowProvider(
    private val delayMillis: Long,
) : AiLibrarianProvider {
    override val id: AiLibrarianProviderId = AiLibrarianProviderId.ON_DEVICE_HEURISTIC

    override val sendsDataOffDevice: Boolean = false

    override suspend fun answer(request: AiLibrarianRequest): AiLibrarianAnswer {
        delay(delayMillis)
        error("timeout expected before completion")
    }
}

private class HangingProvider : AiLibrarianProvider {
    override val id: AiLibrarianProviderId = AiLibrarianProviderId.ON_DEVICE_HEURISTIC

    override val sendsDataOffDevice: Boolean = false

    override suspend fun answer(request: AiLibrarianRequest): AiLibrarianAnswer = awaitCancellation()
}

private class FailingProvider(
    private val kind: AiLibrarianProviderErrorKind,
) : AiLibrarianProvider {
    override val id: AiLibrarianProviderId = AiLibrarianProviderId.ON_DEVICE_HEURISTIC

    override val sendsDataOffDevice: Boolean = false

    override suspend fun answer(request: AiLibrarianRequest): AiLibrarianAnswer = throw AiLibrarianProviderException(kind)
}

private class ThrowingProvider : AiLibrarianProvider {
    override val id: AiLibrarianProviderId = AiLibrarianProviderId.ON_DEVICE_HEURISTIC

    override val sendsDataOffDevice: Boolean = false

    override suspend fun answer(request: AiLibrarianRequest): AiLibrarianAnswer = error("boom")
}

private class FakeAiConsentRepository(
    granted: Boolean,
) : ConsentRepository {
    private val consents =
        MutableStateFlow(
            if (granted) mapOf(ConsentPurpose.AI_LIBRARIAN to record(revoked = false)) else emptyMap(),
        )

    override fun observeConsents(): Flow<Map<ConsentPurpose, ConsentRecord>> = consents

    override suspend fun isGranted(purpose: ConsentPurpose): Boolean = consents.value[purpose]?.granted == true

    override suspend fun grant(purpose: ConsentPurpose): ConsentRecord {
        val granted = record(revoked = false)
        consents.value = consents.value + (purpose to granted)
        return granted
    }

    override suspend fun revoke(purpose: ConsentPurpose): ConsentRecord {
        val revoked = record(revoked = true)
        consents.value = consents.value + (purpose to revoked)
        return revoked
    }

    private companion object {
        fun record(revoked: Boolean): ConsentRecord =
            ConsentRecord(
                purpose = ConsentPurpose.AI_LIBRARIAN,
                consentedVersion = ConsentPurpose.AI_LIBRARIAN.policyVersion,
                grantedAtMillis = 1L,
                revokedAtMillis = if (revoked) 2L else null,
            )
    }
}

/** 蔵書変更を全て記録するfake。AI司書は1件も呼んではならない。 */
private class RecordingLibraryRepository(
    initial: List<LibraryBook>,
) : LibraryRepository {
    val mutations = mutableListOf<String>()
    private val books = MutableStateFlow(initial)

    override fun observeLibrary(): Flow<List<LibraryBook>> = books

    override suspend fun addFromIsbn(rawIsbn: String): AddBookResult {
        mutations += "addFromIsbn"
        return AddBookResult.Failure(AddBookFailure.SAVE, rawIsbn)
    }

    override suspend fun updateBook(
        copyId: String,
        draft: BookEditDraft,
    ): UpdateBookResult {
        mutations += "updateBook"
        return UpdateBookResult.NotFound
    }

    override suspend fun restoreBook(
        previous: LibraryBook,
        expectedCurrent: LibraryBook,
    ): Boolean {
        mutations += "restoreBook"
        return false
    }

    override suspend fun deleteBook(copyId: String): DeleteBookResult {
        mutations += "deleteBook"
        return DeleteBookResult.NotFound
    }

    override suspend fun restoreDeletedBook(book: LibraryBook): RestoreDeletedBookResult {
        mutations += "restoreDeletedBook"
        return RestoreDeletedBookResult.Failure
    }

    override suspend fun previewImport(
        batch: LibraryImportBatch,
        conflictPolicy: ImportConflictPolicy,
    ): ImportPreviewResult {
        mutations += "previewImport"
        error("not used")
    }

    override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult {
        mutations += "applyImport"
        error("not used")
    }
}
