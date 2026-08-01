package dev.ndcshelf.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.ndcshelf.app.domain.ai.llm.LlmCapability
import dev.ndcshelf.app.domain.ai.llm.LlmModelDefinition
import dev.ndcshelf.app.domain.ai.llm.LlmModelInstallFailure
import dev.ndcshelf.app.domain.ai.llm.LlmModelInstallResult
import dev.ndcshelf.app.domain.ai.llm.LlmModelSource
import dev.ndcshelf.app.domain.ai.llm.LlmModelState
import dev.ndcshelf.app.domain.ai.llm.LlmModelStore
import dev.ndcshelf.app.domain.ai.llm.LlmUnsupportedReason
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 端末内LLMモデルの取得・確認・削除のViewModel（docs/adr/0009-on-device-llm-librarian.md）。
 *
 * 実行の不変条件:
 * - [ConsentPurpose.MODEL_DOWNLOAD]へ同意していなければ、ネットワーク取得を一度も開始しない。
 *   端末内ファイルからの導入は通信を伴わないため同意を必要としない。
 * - 端末条件を満たさない場合は取得導線そのものを提供しない（fail-closed）。
 * - 取得・検証・削除はいずれもmain threadで行わない。
 * - 失敗しても直前の検証済みモデルは保持される（[LlmModelStore]の契約）。
 */
class LlmModelViewModel(
    private val modelStore: LlmModelStore,
    private val capabilityProvider: () -> LlmCapability,
    private val consentRepository: ConsentRepository? = null,
    private val downloadSourceFactory: (LlmModelDefinition) -> LlmModelSource,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<LlmModelUiState> = _state.asStateFlow()

    private var installJob: Job? = null

    init {
        viewModelScope.launch {
            (consentRepository?.observeConsents() ?: flowOf(emptyMap())).collect { consents ->
                val granted = consents[ConsentPurpose.MODEL_DOWNLOAD]?.granted == true
                _state.update { current -> current.copy(downloadConsentGranted = granted) }
            }
        }
    }

    fun refresh() {
        _state.update { current -> current.copy(capability = capabilityProvider()).withInstallState() }
    }

    fun grantDownloadConsent() {
        val repository = consentRepository ?: return
        viewModelScope.launch { runCatching { repository.grant(ConsentPurpose.MODEL_DOWNLOAD) } }
    }

    fun revokeDownloadConsent() {
        val repository = consentRepository ?: return
        cancelInstall()
        viewModelScope.launch { runCatching { repository.revoke(ConsentPurpose.MODEL_DOWNLOAD) } }
    }

    /** 配布元からの取得。同意と端末条件を満たすときだけ開始する。 */
    fun startDownload() {
        val current = _state.value
        val model = current.model ?: return
        if (current.capability !is LlmCapability.Supported) {
            fail(LlmModelFailure.DEVICE_UNSUPPORTED)
            return
        }
        if (!current.downloadConsentGranted) {
            fail(LlmModelFailure.NOT_CONSENTED)
            return
        }
        install(model) { downloadSourceFactory(model) }
    }

    /** 端末内のファイルからの導入。通信を伴わないため同意は不要。 */
    fun importFromDevice(source: LlmModelSource) {
        val current = _state.value
        val model = current.model ?: return
        if (current.capability !is LlmCapability.Supported) {
            fail(LlmModelFailure.DEVICE_UNSUPPORTED)
            return
        }
        install(model) { source }
    }

    fun cancelInstall() {
        if (!_state.value.installing) return
        installJob?.cancel()
        installJob = null
        _state.update { current ->
            current.copy(installing = false, progressBytes = 0, failure = LlmModelFailure.CANCELLED)
        }
    }

    /** 導入済みモデルの整合性を再確認する。不一致なら削除される。 */
    fun verifyInstalled() {
        val model = _state.value.model ?: return
        viewModelScope.launch {
            val verified = withContext(ioDispatcher) { modelStore.verifyInstalled(model) }
            _state.update { current ->
                current
                    .copy(failure = if (verified) null else LlmModelFailure.CHECKSUM_MISMATCH)
                    .withInstallState()
            }
        }
    }

    /** モデルとモデル由来ファイルを端末内から全削除する。 */
    fun deleteAll() {
        cancelInstall()
        viewModelScope.launch {
            withContext(ioDispatcher) { modelStore.deleteAll() }
            _state.update { current -> current.copy(failure = null).withInstallState() }
        }
    }

    fun dismissFailure() {
        _state.update { current -> current.copy(failure = null) }
    }

    private fun install(
        model: LlmModelDefinition,
        source: () -> LlmModelSource,
    ) {
        installJob?.cancel()
        _state.update { current ->
            current.copy(installing = true, progressBytes = 0, failure = null)
        }
        installJob =
            viewModelScope.launch {
                val result =
                    try {
                        withContext(ioDispatcher) {
                            modelStore.install(model, source()) { written, _ ->
                                _state.update { current -> current.copy(progressBytes = written) }
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        LlmModelInstallResult.Failed(LlmModelInstallFailure.STORAGE_ERROR)
                    }
                _state.update { current ->
                    current
                        .copy(
                            installing = false,
                            progressBytes = 0,
                            failure =
                                when (result) {
                                    is LlmModelInstallResult.Installed -> null
                                    is LlmModelInstallResult.Failed -> result.reason.toFailure()
                                },
                        ).withInstallState()
                }
            }
    }

    private fun fail(failure: LlmModelFailure) {
        _state.update { current -> current.copy(failure = failure, installing = false) }
    }

    private fun initialState(): LlmModelUiState = LlmModelUiState(capability = capabilityProvider()).withInstallState()

    private fun LlmModelUiState.withInstallState(): LlmModelUiState {
        val model = this.model ?: return copy(installed = false, installedSizeBytes = 0)
        return when (val installState = modelStore.state(model)) {
            is LlmModelState.Installed -> {
                copy(installed = true, installedSizeBytes = installState.fileSizeBytes)
            }

            LlmModelState.NotInstalled -> {
                copy(installed = false, installedSizeBytes = 0)
            }
        }
    }

    private fun LlmModelInstallFailure.toFailure(): LlmModelFailure =
        when (this) {
            LlmModelInstallFailure.TRANSPORT -> LlmModelFailure.TRANSPORT
            LlmModelInstallFailure.SIZE_MISMATCH -> LlmModelFailure.SIZE_MISMATCH
            LlmModelInstallFailure.CHECKSUM_MISMATCH -> LlmModelFailure.CHECKSUM_MISMATCH
            LlmModelInstallFailure.STORAGE_ERROR -> LlmModelFailure.STORAGE_ERROR
        }

    companion object {
        fun factory(
            modelStore: LlmModelStore,
            capabilityProvider: () -> LlmCapability,
            consentRepository: ConsentRepository?,
            downloadSourceFactory: (LlmModelDefinition) -> LlmModelSource,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    LlmModelViewModel(
                        modelStore = modelStore,
                        capabilityProvider = capabilityProvider,
                        consentRepository = consentRepository,
                        downloadSourceFactory = downloadSourceFactory,
                    )
                }
            }
    }
}

/** 利用者へ提示するモデル管理の失敗種別。文言はstrings.xmlで対応付ける。 */
enum class LlmModelFailure {
    NOT_CONSENTED,
    DEVICE_UNSUPPORTED,
    TRANSPORT,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    STORAGE_ERROR,
    CANCELLED,
}

data class LlmModelUiState(
    val capability: LlmCapability,
    val downloadConsentGranted: Boolean = false,
    val installing: Boolean = false,
    val progressBytes: Long = 0,
    val installed: Boolean = false,
    val installedSizeBytes: Long = 0,
    val failure: LlmModelFailure? = null,
) {
    /** 台帳の既定モデル。対応モデルが無ければnull。 */
    val model: LlmModelDefinition?
        get() = (capability as? LlmCapability.Supported)?.model ?: dev.ndcshelf.app.domain.ai.llm.LlmModelCatalog.defaultModel

    val supported: Boolean get() = capability is LlmCapability.Supported

    val unsupportedReasons: List<LlmUnsupportedReason>
        get() = (capability as? LlmCapability.Unsupported)?.reasons.orEmpty()

    /** 0.0〜1.0の取得進捗。サイズ不明時は0。 */
    val progressFraction: Float
        get() {
            val total = model?.sizeBytes ?: return 0f
            if (total <= 0) return 0f
            return (progressBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
}
