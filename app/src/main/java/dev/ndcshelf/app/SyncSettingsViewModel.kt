package dev.ndcshelf.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.ndcshelf.app.data.sync.E2eeSyncCoordinator
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRepository
import dev.ndcshelf.app.domain.sync.SyncActionResult
import dev.ndcshelf.app.domain.sync.SyncConfigurationStatus
import dev.ndcshelf.app.domain.sync.SyncDeletionReceipt
import dev.ndcshelf.app.domain.sync.SyncDeviceInfo
import dev.ndcshelf.app.domain.sync.SyncFailure
import dev.ndcshelf.app.domain.sync.SyncInvite
import dev.ndcshelf.app.domain.sync.SyncJoinCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * データ管理画面の同期セクション状態。同期の有効化・手動同期・端末管理・
 * 全削除・停止のUI操作をcoordinatorへ委譲する。
 */
class SyncSettingsViewModel(
    private val coordinator: E2eeSyncCoordinator,
    private val consentRepository: ConsentRepository,
    private val deviceName: String,
) : ViewModel() {
    data class SyncUiState(
        val busy: Boolean = false,
        val lastFailure: SyncFailure? = null,
        val inviteCode: String? = null,
        val inviteExpiresAtMillis: Long? = null,
        val joinVerificationCode: String? = null,
        val joinCandidates: List<SyncJoinCandidate> = emptyList(),
        val deletionReceipt: SyncDeletionReceipt? = null,
        val lastSyncApplied: Int? = null,
    )

    private val mutableUiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = mutableUiState.asStateFlow()

    val configuration: StateFlow<SyncConfigurationStatus> =
        coordinator
            .observeConfiguration()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncConfigurationStatus())

    val devices: StateFlow<List<SyncDeviceInfo>> =
        coordinator
            .observeDevices()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncConsentGranted: StateFlow<Boolean> =
        consentRepository
            .observeConsents()
            .map { consents -> consents[ConsentPurpose.LIBRARY_SYNC]?.granted == true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun grantSyncConsent() {
        viewModelScope.launch { consentRepository.grant(ConsentPurpose.LIBRARY_SYNC) }
    }

    fun createLibrary(folderUri: String) {
        launchAction {
            coordinator.createLibrary(deviceName, BACKEND_TYPE, folderUri)
        }
    }

    fun joinLibrary(
        folderUri: String,
        inviteCode: String,
    ) {
        launchAction {
            coordinator.joinLibrary(deviceName, BACKEND_TYPE, folderUri, inviteCode)
        }
    }

    fun completeJoin() {
        launchAction { coordinator.completeJoin() }
    }

    fun syncNow() {
        launchAction { coordinator.syncNow() }
    }

    fun createInvite() {
        launchAction {
            val result = coordinator.createInvite()
            if (result is SyncActionResult.Success) {
                coordinator.lastCreatedInvite?.let { invite: SyncInvite ->
                    mutableUiState.value =
                        mutableUiState.value.copy(
                            inviteCode = invite.encode(),
                            inviteExpiresAtMillis = invite.expiresAtMillis,
                        )
                }
            }
            result
        }
    }

    fun dismissInvite() {
        mutableUiState.value =
            mutableUiState.value.copy(inviteCode = null, inviteExpiresAtMillis = null, joinCandidates = emptyList())
    }

    fun refreshJoinCandidates() {
        viewModelScope.launch {
            val candidates = coordinator.pendingJoinRequests()
            mutableUiState.value = mutableUiState.value.copy(joinCandidates = candidates)
        }
    }

    fun approveJoin(candidate: SyncJoinCandidate) {
        launchAction {
            val result = coordinator.approveJoin(candidate)
            if (result is SyncActionResult.Success) {
                mutableUiState.value =
                    mutableUiState.value.copy(
                        joinCandidates = emptyList(),
                        inviteCode = null,
                        inviteExpiresAtMillis = null,
                    )
            }
            result
        }
    }

    fun revokeDevice(deviceId: String) {
        launchAction { coordinator.revokeDevice(deviceId) }
    }

    fun purgeRemote() {
        viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(busy = true, lastFailure = null)
            val result = coordinator.purgeRemote()
            mutableUiState.value =
                result.fold(
                    onSuccess = { receipt ->
                        mutableUiState.value.copy(busy = false, deletionReceipt = receipt)
                    },
                    onFailure = {
                        mutableUiState.value.copy(busy = false)
                    },
                )
        }
    }

    fun dismissDeletionReceipt() {
        mutableUiState.value = mutableUiState.value.copy(deletionReceipt = null)
    }

    fun stopSync() {
        viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(busy = true, lastFailure = null)
            coordinator.stopSync()
            mutableUiState.value = SyncUiState()
        }
    }

    fun dismissFailure() {
        mutableUiState.value = mutableUiState.value.copy(lastFailure = null)
    }

    private fun launchAction(action: suspend () -> SyncActionResult) {
        viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(busy = true, lastFailure = null)
            when (val result = action()) {
                is SyncActionResult.Success -> {
                    mutableUiState.value =
                        mutableUiState.value.copy(
                            busy = false,
                            joinVerificationCode = null,
                            lastSyncApplied = result.appliedOperationCount,
                        )
                }

                is SyncActionResult.JoinPending -> {
                    mutableUiState.value =
                        mutableUiState.value.copy(busy = false, joinVerificationCode = result.verificationCode)
                }

                is SyncActionResult.Failure -> {
                    mutableUiState.value =
                        mutableUiState.value.copy(busy = false, lastFailure = result.failure)
                }
            }
        }
    }

    companion object {
        private const val BACKEND_TYPE = "saf-folder"

        fun factory(
            coordinator: E2eeSyncCoordinator,
            consentRepository: ConsentRepository,
            deviceName: String,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    SyncSettingsViewModel(coordinator, consentRepository, deviceName)
                }
            }
    }
}
