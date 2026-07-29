package dev.ndcshelf.app

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
import dev.ndcshelf.app.domain.model.SeriesWatchOverview
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.SeriesWatchCheckResult
import dev.ndcshelf.app.domain.repository.SeriesWatchMutationResult
import dev.ndcshelf.app.domain.repository.SeriesWatchRepository
import dev.ndcshelf.app.domain.repository.SeriesWatchScheduler
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelConsentTest {
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
    fun enablingWatchWithoutConsentRequestsConsentInsteadOfEnabling() =
        runTest(dispatcher) {
            val watches = FakeWatchRepository()
            val consents = FakeConsentRepository()
            val viewModel = viewModel(watches, consents)

            viewModel.setSeriesWatchEnabled("series-1", true)

            assertEquals("series-1", viewModel.seriesWatchConsentRequest.value)
            assertTrue(watches.enabledCalls.isEmpty())
        }

    @Test
    fun grantingConsentEnablesPendingSeriesAndReconcilesScheduler() =
        runTest(dispatcher) {
            val watches = FakeWatchRepository()
            val consents = FakeConsentRepository()
            val scheduler = FakeScheduler()
            val viewModel = viewModel(watches, consents, scheduler)
            scheduler.reconciled.clear()
            viewModel.setSeriesWatchEnabled("series-1", true)

            viewModel.grantSeriesWatchConsent()

            assertNull(viewModel.seriesWatchConsentRequest.value)
            assertTrue(consents.granted.contains(ConsentPurpose.SERIES_RELEASE_WATCH))
            assertEquals(listOf("series-1" to true), watches.enabledCalls)
            assertEquals(listOf(true), scheduler.reconciled)
        }

    @Test
    fun decliningConsentKeepsWatchDisabledAndSendsNothing() =
        runTest(dispatcher) {
            val watches = FakeWatchRepository()
            val consents = FakeConsentRepository()
            val viewModel = viewModel(watches, consents)
            viewModel.setSeriesWatchEnabled("series-1", true)

            viewModel.declineSeriesWatchConsent()

            assertNull(viewModel.seriesWatchConsentRequest.value)
            assertTrue(watches.enabledCalls.isEmpty())
            assertTrue(consents.granted.isEmpty())
        }

    @Test
    fun disablingWatchNeverRequiresConsent() =
        runTest(dispatcher) {
            val watches = FakeWatchRepository()
            val consents = FakeConsentRepository()
            val viewModel = viewModel(watches, consents)

            viewModel.setSeriesWatchEnabled("series-1", false)

            assertNull(viewModel.seriesWatchConsentRequest.value)
            assertEquals(listOf("series-1" to false), watches.enabledCalls)
        }

    @Test
    fun revokingConsentStopsScheduledWork() =
        runTest(dispatcher) {
            val watches = FakeWatchRepository()
            val consents = FakeConsentRepository()
            consents.granted += ConsentPurpose.SERIES_RELEASE_WATCH
            val scheduler = FakeScheduler()
            val viewModel = viewModel(watches, consents, scheduler)
            scheduler.reconciled.clear()

            viewModel.revokeConsent(ConsentPurpose.SERIES_RELEASE_WATCH)

            assertFalse(consents.granted.contains(ConsentPurpose.SERIES_RELEASE_WATCH))
            assertEquals(listOf(false), scheduler.reconciled)
        }

    private fun viewModel(
        watches: FakeWatchRepository,
        consents: FakeConsentRepository,
        scheduler: FakeScheduler = FakeScheduler(),
    ): MainViewModel =
        MainViewModel(
            repository = FakeConsentTestRepository(),
            importIoDispatcher = dispatcher,
            importComputationDispatcher = dispatcher,
            seriesWatchRepository = watches,
            seriesWatchScheduler = scheduler,
            consentRepository = consents,
        )
}

private class FakeScheduler : SeriesWatchScheduler {
    val reconciled = mutableListOf<Boolean>()

    override fun reconcile(enabled: Boolean) {
        reconciled += enabled
    }
}

private class FakeConsentRepository : ConsentRepository {
    val granted = mutableSetOf<ConsentPurpose>()

    override fun observeConsents(): Flow<Map<ConsentPurpose, ConsentRecord>> = emptyFlow()

    override suspend fun isGranted(purpose: ConsentPurpose): Boolean = purpose in granted

    override suspend fun grant(purpose: ConsentPurpose): ConsentRecord {
        granted += purpose
        return ConsentRecord(purpose, purpose.policyVersion, 0L, null)
    }

    override suspend fun revoke(purpose: ConsentPurpose): ConsentRecord? {
        granted -= purpose
        return ConsentRecord(purpose, purpose.policyVersion, 0L, 1L)
    }
}

private class FakeWatchRepository : SeriesWatchRepository {
    val enabledCalls = mutableListOf<Pair<String, Boolean>>()

    override fun observeWatches(): Flow<List<SeriesWatchOverview>> = flowOf(emptyList())

    override suspend fun setEnabled(
        seriesId: String,
        enabled: Boolean,
    ): SeriesWatchMutationResult {
        enabledCalls += seriesId to enabled
        return SeriesWatchMutationResult.Updated
    }

    override suspend fun hasEnabledWatches(): Boolean = enabledCalls.any { it.second }

    override suspend fun checkEnabledWatches(): SeriesWatchCheckResult = SeriesWatchCheckResult.Success(emptyList())

    override suspend fun markNotified(candidateIds: List<String>) = Unit
}

private class FakeConsentTestRepository : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryBook>> = flowOf(emptyList())

    override suspend fun addFromIsbn(rawIsbn: String): AddBookResult = AddBookResult.Failure(AddBookFailure.SAVE, rawIsbn)

    override suspend fun updateBook(
        copyId: String,
        draft: BookEditDraft,
    ): UpdateBookResult = UpdateBookResult.NotFound

    override suspend fun restoreBook(
        previous: LibraryBook,
        expectedCurrent: LibraryBook,
    ): Boolean = false

    override suspend fun deleteBook(copyId: String): DeleteBookResult = DeleteBookResult.NotFound

    override suspend fun restoreDeletedBook(book: LibraryBook): RestoreDeletedBookResult = RestoreDeletedBookResult.Failure

    override suspend fun previewImport(
        batch: LibraryImportBatch,
        conflictPolicy: ImportConflictPolicy,
    ): ImportPreviewResult = error("Not used in consent tests")

    override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult = error("Not used in consent tests")
}
