package dev.ndcshelf.app.domain.ai.llm

/**
 * 端末能力の判定。予算外端末ではLLMを起動しない（OOMを例外処理で回復する設計に
 * しない）ため、取得・初期化の前に必ず[LlmCapabilityChecker.evaluate]を通す。
 */

/** LLMを提供できない理由。UI文言はstrings.xmlで対応付ける。 */
enum class LlmUnsupportedReason {
    /** ビルドflagまたは利用者設定で無効。 */
    DISABLED,

    /** 許可済みモデルが台帳に無い（配布停止・廃止を含む）。 */
    NO_MODEL_AVAILABLE,

    /** API levelが不足。 */
    SDK_TOO_OLD,

    /** 対応ABIを持たない。 */
    ABI_UNSUPPORTED,

    /** 物理RAMが不足。 */
    INSUFFICIENT_RAM,

    /** OSがlow-RAM端末と申告している。 */
    LOW_RAM_DEVICE,

    /** モデル導入に必要な空き容量が不足。 */
    INSUFFICIENT_STORAGE,

    /** 推論runtimeを読み込めない（ネイティブライブラリ欠落など）。 */
    RUNTIME_UNAVAILABLE,
}

/** 端末側の実測値。Androidに依存しないため、JVMテストで全分岐を再現できる。 */
data class LlmDeviceProfile(
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val totalRamBytes: Long,
    val availableStorageBytes: Long,
    val isLowRamDevice: Boolean,
    val runtimeAvailable: Boolean,
)

sealed interface LlmCapability {
    data class Supported(
        val model: LlmModelDefinition,
    ) : LlmCapability

    data class Unsupported(
        val reasons: List<LlmUnsupportedReason>,
    ) : LlmCapability {
        init {
            require(reasons.isNotEmpty()) { "Unsupported requires at least one reason" }
        }

        val primaryReason: LlmUnsupportedReason get() = reasons.first()
    }
}

/**
 * fail-closedの能力判定。判定できない値が一つでもあれば[LlmCapability.Unsupported]を返す。
 *
 * 判定順は利用者への説明のしやすさ順（無効 → モデル無し → 端末条件）で、
 * 該当する理由はすべて列挙する。
 */
object LlmCapabilityChecker {
    fun evaluate(
        profile: LlmDeviceProfile,
        model: LlmModelDefinition?,
        enabled: Boolean,
    ): LlmCapability {
        val reasons = mutableListOf<LlmUnsupportedReason>()
        if (!enabled) reasons += LlmUnsupportedReason.DISABLED
        if (model == null) reasons += LlmUnsupportedReason.NO_MODEL_AVAILABLE
        if (!profile.runtimeAvailable) reasons += LlmUnsupportedReason.RUNTIME_UNAVAILABLE
        if (model != null) {
            if (profile.sdkInt < model.minSdkInt) reasons += LlmUnsupportedReason.SDK_TOO_OLD
            if (profile.supportedAbis.none { abi -> abi in model.requiredAbis }) {
                reasons += LlmUnsupportedReason.ABI_UNSUPPORTED
            }
            if (profile.totalRamBytes < model.minTotalRamBytes) {
                reasons += LlmUnsupportedReason.INSUFFICIENT_RAM
            }
            if (profile.availableStorageBytes < model.requiredFreeBytes) {
                reasons += LlmUnsupportedReason.INSUFFICIENT_STORAGE
            }
        }
        if (profile.isLowRamDevice) reasons += LlmUnsupportedReason.LOW_RAM_DEVICE
        return if (reasons.isEmpty() && model != null) {
            LlmCapability.Supported(model)
        } else {
            LlmCapability.Unsupported(reasons.ifEmpty { listOf(LlmUnsupportedReason.NO_MODEL_AVAILABLE) })
        }
    }

    /**
     * モデル取得を始めてよいか。取得後に起動できない端末で数GiBを
     * ダウンロードさせないため、取得前も同じ条件で判定する。
     */
    fun canAcquire(
        profile: LlmDeviceProfile,
        model: LlmModelDefinition?,
        enabled: Boolean,
    ): Boolean = evaluate(profile, model, enabled) is LlmCapability.Supported
}
