package dev.ndcshelf.app

import dev.ndcshelf.app.domain.model.WorkVariant
import dev.ndcshelf.app.domain.model.WorkVariantEditor
import dev.ndcshelf.app.domain.model.WorkVariantSuggestion
import dev.ndcshelf.app.domain.model.WorkVariantSuggestionConfidence
import dev.ndcshelf.app.domain.repository.WorkGroupMutationResult
import dev.ndcshelf.app.domain.repository.WorkGroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkVariantViewModelTest {
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
    fun unknownWorkIdBecomesErrorInsteadOfCrash() =
        runTest(dispatcher) {
            val viewModel = WorkVariantViewModel(FakeWorkGroupRepository(editors = emptyMap()), "missing")

            assertEquals(WorkVariantUiState.Error, viewModel.state.value)
        }

    @Test
    fun existingWorkIdLoadsEditor() =
        runTest(dispatcher) {
            val editor = editor("work-1")
            val viewModel =
                WorkVariantViewModel(
                    FakeWorkGroupRepository(editors = mapOf("work-1" to editor)),
                    "work-1",
                )

            assertEquals(WorkVariantUiState.Ready(editor), viewModel.state.value)
        }

    @Test
    fun linkConflictSurfacesConflictState() =
        runTest(dispatcher) {
            val editor = editor("work-1", suggestionWorkId = "work-2")
            val repository =
                FakeWorkGroupRepository(
                    editors = mapOf("work-1" to editor),
                    linkResult = WorkGroupMutationResult.Conflict,
                )
            val viewModel = WorkVariantViewModel(repository, "work-1")

            viewModel.linkVariant("work-2", enableSeriesSubstitution = false)

            assertEquals(WorkVariantUiState.Conflict, viewModel.state.value)
        }

    @Test
    fun successfulLinkReloadsEditor() =
        runTest(dispatcher) {
            val editor = editor("work-1", suggestionWorkId = "work-2")
            val repository =
                FakeWorkGroupRepository(
                    editors = mapOf("work-1" to editor),
                    linkResult = WorkGroupMutationResult.Linked("group-1"),
                )
            val viewModel = WorkVariantViewModel(repository, "work-1")

            viewModel.linkVariant("work-2", enableSeriesSubstitution = true)

            assertTrue(viewModel.state.value is WorkVariantUiState.Ready)
            assertEquals(1, repository.linkCalls)
        }

    @Test
    fun unlinkFailureSurfacesErrorState() =
        runTest(dispatcher) {
            val editor = editor("work-1")
            val repository =
                FakeWorkGroupRepository(
                    editors = mapOf("work-1" to editor),
                    unlinkResult = WorkGroupMutationResult.Failure,
                )
            val viewModel = WorkVariantViewModel(repository, "work-1")

            viewModel.unlinkVariant("membership-1")

            assertEquals(WorkVariantUiState.Error, viewModel.state.value)
        }

    private fun editor(
        workId: String,
        suggestionWorkId: String? = null,
    ): WorkVariantEditor {
        val source = WorkVariant(workId, "作品", "著者", emptyList())
        val suggestions =
            suggestionWorkId?.let { targetId ->
                listOf(
                    WorkVariantSuggestion(
                        WorkVariant(targetId, "作品（文庫版）", "著者", emptyList()),
                        WorkVariantSuggestionConfidence.HIGH,
                        "タイトルと著者が一致",
                    ),
                )
            } ?: emptyList()
        return WorkVariantEditor(source, group = null, groupMembers = emptyList(), suggestions = suggestions)
    }
}

private class FakeWorkGroupRepository(
    private val editors: Map<String, WorkVariantEditor>,
    private val linkResult: WorkGroupMutationResult = WorkGroupMutationResult.Failure,
    private val unlinkResult: WorkGroupMutationResult = WorkGroupMutationResult.Failure,
    private val substitutionResult: WorkGroupMutationResult = WorkGroupMutationResult.Failure,
) : WorkGroupRepository {
    var linkCalls: Int = 0
        private set

    override suspend fun editorFor(workId: String): WorkVariantEditor? = editors[workId]

    override suspend fun link(
        sourceWorkId: String,
        targetWorkId: String,
        expectedSourceTitle: String,
        expectedTargetTitle: String,
        seriesSubstitutionEnabled: Boolean,
    ): WorkGroupMutationResult {
        linkCalls += 1
        return linkResult
    }

    override suspend fun unlink(membershipId: String): WorkGroupMutationResult = unlinkResult

    override suspend fun setSeriesSubstitution(
        groupId: String,
        enabled: Boolean,
    ): WorkGroupMutationResult = substitutionResult
}
