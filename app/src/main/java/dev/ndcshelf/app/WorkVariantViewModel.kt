package dev.ndcshelf.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import dev.ndcshelf.app.domain.model.WorkVariantEditor
import dev.ndcshelf.app.domain.repository.WorkGroupMutationResult
import dev.ndcshelf.app.domain.repository.WorkGroupRepository
import dev.ndcshelf.app.ui.navigation.WorkVariantRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 版違い（work variant）編集画面のViewModel。routeのworkIdをSavedStateHandle
 * から復元するため、プロセス再生成後も編集対象を失わない。
 */
class WorkVariantViewModel(
    private val workGroupRepository: WorkGroupRepository,
    private val workId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<WorkVariantUiState>(WorkVariantUiState.Idle)
    val state: StateFlow<WorkVariantUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        reload()
    }

    fun linkVariant(
        targetWorkId: String,
        enableSeriesSubstitution: Boolean,
    ) {
        val editor = (_state.value as? WorkVariantUiState.Ready)?.editor ?: return
        val target = editor.suggestions.firstOrNull { it.work.workId == targetWorkId }?.work ?: return
        job?.cancel()
        job =
            viewModelScope.launch {
                _state.value = WorkVariantUiState.Saving
                when (
                    workGroupRepository.link(
                        sourceWorkId = editor.source.workId,
                        targetWorkId = target.workId,
                        expectedSourceTitle = editor.source.title,
                        expectedTargetTitle = target.title,
                        seriesSubstitutionEnabled = enableSeriesSubstitution,
                    )
                ) {
                    is WorkGroupMutationResult.Linked -> loadEditor()
                    WorkGroupMutationResult.Conflict -> _state.value = WorkVariantUiState.Conflict
                    WorkGroupMutationResult.Invalid -> _state.value = WorkVariantUiState.Invalid
                    else -> _state.value = WorkVariantUiState.Error
                }
            }
    }

    fun unlinkVariant(membershipId: String) {
        job?.cancel()
        job =
            viewModelScope.launch {
                _state.value = WorkVariantUiState.Saving
                when (workGroupRepository.unlink(membershipId)) {
                    WorkGroupMutationResult.Unlinked -> loadEditor()
                    WorkGroupMutationResult.Conflict -> _state.value = WorkVariantUiState.Conflict
                    WorkGroupMutationResult.Invalid -> _state.value = WorkVariantUiState.Invalid
                    else -> _state.value = WorkVariantUiState.Error
                }
            }
    }

    fun setSeriesSubstitution(
        groupId: String,
        enabled: Boolean,
    ) {
        job?.cancel()
        job =
            viewModelScope.launch {
                _state.value = WorkVariantUiState.Saving
                when (workGroupRepository.setSeriesSubstitution(groupId, enabled)) {
                    WorkGroupMutationResult.Updated -> loadEditor()
                    WorkGroupMutationResult.Conflict -> _state.value = WorkVariantUiState.Conflict
                    WorkGroupMutationResult.Invalid -> _state.value = WorkVariantUiState.Invalid
                    else -> _state.value = WorkVariantUiState.Error
                }
            }
    }

    fun reload() {
        job?.cancel()
        job =
            viewModelScope.launch {
                _state.value = WorkVariantUiState.Loading
                loadEditor()
            }
    }

    private suspend fun loadEditor() {
        _state.value = workGroupRepository
            .editorFor(workId)
            ?.let(WorkVariantUiState::Ready)
            ?: WorkVariantUiState.Error
    }

    companion object {
        fun factory(workGroupRepository: WorkGroupRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val handle: SavedStateHandle = createSavedStateHandle()
                    WorkVariantViewModel(
                        workGroupRepository = workGroupRepository,
                        workId = handle.toRoute<WorkVariantRoute>().workId,
                    )
                }
            }
    }
}

sealed interface WorkVariantUiState {
    data object Idle : WorkVariantUiState

    data object Loading : WorkVariantUiState

    data class Ready(
        val editor: WorkVariantEditor,
    ) : WorkVariantUiState

    data object Saving : WorkVariantUiState

    data object Conflict : WorkVariantUiState

    data object Invalid : WorkVariantUiState

    data object Error : WorkVariantUiState
}
