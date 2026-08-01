package dev.ndcshelf.app.domain.ai.llm

import java.io.File

/** テスト用の許可済みモデル定義。実在URL・実在checksumを含めない。 */
internal fun testModelDefinition(
    id: String = "test-model",
    version: String = "1.0.0",
    sizeBytes: Long = 16L,
    sha256: String = "0".repeat(64),
    downloadUrl: String = "https://huggingface.co/ndc-shelf-test/model/resolve/main/model.bin",
    minSdkInt: Int = 24,
    requiredAbis: Set<String> = setOf("arm64-v8a"),
    minTotalRamBytes: Long = 4L * 1024 * 1024 * 1024,
    requiredFreeBytes: Long = sizeBytes * 2,
    retiredOn: String? = null,
): LlmModelDefinition =
    LlmModelDefinition(
        id = id,
        version = version,
        displayName = "テストモデル",
        runtime = LlmRuntimeId.FAKE,
        downloadUrl = downloadUrl,
        fileName = "model.bin",
        sizeBytes = sizeBytes,
        sha256 = sha256,
        licenseSpdxId = "Apache-2.0",
        licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
        sourceUrl = "https://huggingface.co/ndc-shelf-test/model",
        verifiedOn = "2026-08-01",
        minSdkInt = minSdkInt,
        requiredAbis = requiredAbis,
        minTotalRamBytes = minTotalRamBytes,
        requiredFreeBytes = requiredFreeBytes,
        contextTokens = 2048,
        addedOn = "2026-08-01",
        retiredOn = retiredOn,
    )

internal fun supportedProfile(
    sdkInt: Int = 34,
    supportedAbis: List<String> = listOf("arm64-v8a"),
    totalRamBytes: Long = 8L * 1024 * 1024 * 1024,
    availableStorageBytes: Long = 8L * 1024 * 1024 * 1024,
    isLowRamDevice: Boolean = false,
    runtimeAvailable: Boolean = true,
): LlmDeviceProfile =
    LlmDeviceProfile(
        sdkInt = sdkInt,
        supportedAbis = supportedAbis,
        totalRamBytes = totalRamBytes,
        availableStorageBytes = availableStorageBytes,
        isLowRamDevice = isLowRamDevice,
        runtimeAvailable = runtimeAvailable,
    )

/**
 * 実推論を行わない疑似runtime。契約・失敗分類・キャンセル・出力検証だけを検証する。
 *
 * 実モデルの回答品質・OOM・発熱はこのfakeでは検証できない（実機測定が必要）。
 */
internal class FakeLlmRuntime(
    private val response: (String) -> String = { "{}" },
    private val openFailure: LlmFailureKind? = null,
    private val generateFailure: LlmFailureKind? = null,
    private val onGenerate: suspend () -> Unit = {},
) : LlmInferenceRuntime {
    override val runtimeId: LlmRuntimeId = LlmRuntimeId.FAKE

    override val runtimeVersion: String = "fake-1"

    var openCount: Int = 0
        private set

    var closeCount: Int = 0
        private set

    var lastPrompt: String? = null
        private set

    override suspend fun open(request: LlmLoadRequest): LlmSession {
        openCount += 1
        openFailure?.let { kind -> throw LlmRuntimeException(kind) }
        return object : LlmSession {
            override suspend fun generate(
                prompt: String,
                maxOutputTokens: Int,
            ): String {
                lastPrompt = prompt
                onGenerate()
                generateFailure?.let { kind -> throw LlmRuntimeException(kind) }
                return response(prompt)
            }

            override fun close() {
                closeCount += 1
            }
        }
    }
}

/** 常に導入済みを返すテスト用store。 */
internal class FakeLlmModelStore(
    private val file: File?,
    private val verifyResult: Boolean = true,
) : LlmModelStore {
    override fun state(definition: LlmModelDefinition): LlmModelState =
        file?.let { value ->
            LlmModelState.Installed(definition, value.length(), 0L)
        } ?: LlmModelState.NotInstalled

    override fun installedFile(definition: LlmModelDefinition): File? = file

    var verifyCount: Int = 0
        private set

    override fun verifyInstalled(definition: LlmModelDefinition): Boolean {
        verifyCount += 1
        return verifyResult
    }

    override suspend fun install(
        definition: LlmModelDefinition,
        source: LlmModelSource,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
    ): LlmModelInstallResult = LlmModelInstallResult.Failed(LlmModelInstallFailure.STORAGE_ERROR)

    override fun delete(definition: LlmModelDefinition): Boolean = true

    override fun deleteAll(): Boolean = true
}
