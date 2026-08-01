package dev.ndcshelf.app.domain.ai.llm

/**
 * 端末内LLMのモデル台帳（設計判断はdocs/adr/0009-on-device-llm-librarian.md）。
 *
 * 台帳に無いモデルは取得も読み込みもできない（allowlist方式・fail-closed）。
 * 任意のURLやファイルを読み込む汎用プラグイン機構は提供しない。
 */

/** 推論runtimeの識別子。診断へ記録する値でもある。 */
enum class LlmRuntimeId {
    /** テストとfallback検証で使う疑似runtime。実推論を行わない。 */
    FAKE,

    /** 端末内のネイティブ推論runtime。ADRで固定した実装を指す。 */
    NATIVE,
}

/**
 * 許可済みモデルの定義。
 *
 * [sha256]と[sizeBytes]は取得・導入時に必ず照合し、一致しないファイルは
 * 有効化しない。[downloadUrl]のhostは[LlmModelCatalog.ALLOWED_HOSTS]に
 * 含まれていなければならない。
 */
data class LlmModelDefinition(
    /** 台帳内で一意なID。診断ログへ記録できる固定値。 */
    val id: String,
    /** モデルversion。互換性判定と診断ログに使う。 */
    val version: String,
    /** 画面に表示する名称。 */
    val displayName: String,
    val runtime: LlmRuntimeId,
    /** 取得元URL。HTTPSかつ許可hostのみ。 */
    val downloadUrl: String,
    /** 端末内へ保存するときのファイル名。 */
    val fileName: String,
    /** 期待ファイルサイズ（バイト）。ダウンロードの上限でもある。 */
    val sizeBytes: Long,
    /** 期待SHA-256（小文字hex 64桁）。 */
    val sha256: String,
    val licenseSpdxId: String,
    val licenseUrl: String,
    /** モデルカード等の一次情報URL。 */
    val sourceUrl: String,
    /** 一次情報を確認した日（ISO-8601）。 */
    val verifiedOn: String,
    /** 必要な最小API level。 */
    val minSdkInt: Int,
    /** 必要なABI。いずれかを端末が持つこと。 */
    val requiredAbis: Set<String>,
    /** 必要な物理RAM合計（バイト）。 */
    val minTotalRamBytes: Long,
    /** 導入に必要な空き容量（バイト）。展開・検証の一時領域を含む。 */
    val requiredFreeBytes: Long,
    /** モデルが扱えるcontext長（token）。 */
    val contextTokens: Int,
    /** 台帳へ追加した日（ISO-8601）。 */
    val addedOn: String,
    /** 廃止日（ISO-8601）。過ぎたversionは新規取得を止める。nullなら未定。 */
    val retiredOn: String? = null,
    /** 既知の制約。UIとADRで同じ文言を使う。 */
    val knownLimitations: List<String> = emptyList(),
) {
    init {
        require(SHA256_HEX.matches(sha256)) { "sha256 must be 64 lowercase hex characters" }
        require(sizeBytes in 1..MAX_MODEL_BYTES) { "sizeBytes must be within the model size budget" }
        require(requiredFreeBytes >= sizeBytes) { "requiredFreeBytes must cover the model itself" }
        require(requiredAbis.isNotEmpty()) { "requiredAbis must not be empty" }
        require(LlmModelUrlPolicy.isAllowed(downloadUrl)) { "downloadUrl must be an allowed HTTPS URL" }
        // idとversionとfileNameは端末内のパス組み立てへ入るため、区切りと親参照を許さない。
        listOf(id, version, fileName).forEach { segment ->
            require(segment.isNotBlank()) { "path segments must not be blank" }
            require(PATH_SEGMENT.matches(segment)) { "path segments must not contain separators or '..'" }
        }
    }

    companion object {
        /** 台帳へ載せられるモデルの上限。これを超える定義は受け付けない。 */
        const val MAX_MODEL_BYTES: Long = 3L * 1024 * 1024 * 1024

        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        /** 端末内パスへ使える文字。区切り・親参照・制御文字を許さない。 */
        private val PATH_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}

/**
 * モデル取得URLの検査。NDLのcover URLと同じ考え方で、scheme・host・port・
 * userInfo・queryまで固定し、redirectは呼び出し側で拒否する。
 */
object LlmModelUrlPolicy {
    /** モデル取得を許可するhost。ここに無いhostへは一切接続しない。 */
    val ALLOWED_HOSTS: Set<String> = setOf("huggingface.co", "cdn-lfs-us-1.hf.co")

    fun isAllowed(url: String): Boolean =
        runCatching {
            val uri = java.net.URI(url)
            uri.scheme?.lowercase(java.util.Locale.ROOT) == "https" &&
                uri.host?.lowercase(java.util.Locale.ROOT) in ALLOWED_HOSTS &&
                uri.userInfo == null &&
                uri.port in ALLOWED_PORTS &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                !uri.rawPath.isNullOrBlank() &&
                ".." !in uri.rawPath
        }.getOrDefault(false)

    private val ALLOWED_PORTS = setOf(-1, 443)
}

/**
 * 許可済みモデルの台帳。
 *
 * 追加・廃止はADRとdocs/LOCAL_LLM_MODELS.mdの更新を伴う。実行時に台帳へ
 * 項目を足す経路は存在しない。
 */
object LlmModelCatalog {
    /** 既定で提示するモデル。空の場合、LLM経路は起動しない（fail-closed）。 */
    val models: List<LlmModelDefinition> = emptyList()

    val defaultModel: LlmModelDefinition? get() = models.firstOrNull { model -> model.retiredOn == null }

    fun findById(id: String): LlmModelDefinition? = models.firstOrNull { model -> model.id == id }
}
