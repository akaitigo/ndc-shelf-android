package dev.ndcshelf.app.domain.ai.llm

import java.util.Locale

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
 * モデル取得URLの検査。
 *
 * 台帳へ書けるURL（[isAllowed]）と、そこから1回だけ追従してよいリダイレクト先
 * （[isAllowedRedirectTarget]）を分けて定義する。
 *
 * 配布元のHugging Faceは`/resolve/`から署名付きCDNへ302で誘導し、リージョンや
 * ストレージ方式によって`us.aws.cdn.hf.co`・`cas-bridge.xethub.hf.co`・
 * `cdn-lfs-*.hf.co`などへ振り分ける。ホスト名を固定できないため、追従先は
 * `hf.co`配下に限る接尾辞一致で判定する。追従は1回だけで、2回目以降は失敗にする。
 */
object LlmModelUrlPolicy {
    /** 台帳のURLに書けるhost。ここに無いhostへは最初の要求すら行わない。 */
    val ALLOWED_HOSTS: Set<String> = setOf("huggingface.co")

    /** リダイレクト追従を許すドメイン。hostが完全一致するか、この接尾辞のサブドメインだけ。 */
    val ALLOWED_REDIRECT_DOMAINS: Set<String> = setOf("hf.co", "huggingface.co")

    /** 追従を許すリダイレクトの回数。 */
    const val MAX_REDIRECTS: Int = 1

    /**
     * 台帳へ書いてよいURLか。署名や可変パラメータを持てないよう、queryとfragmentを
     * 一切許可しない（URLが実質的に固定であることを保証する）。
     */
    fun isAllowed(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.host?.lowercase(Locale.ROOT) in ALLOWED_HOSTS &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    }

    /**
     * リダイレクト先として追従してよいURLか。
     *
     * CDNの署名付きURLは`Expires`・`Signature`などのqueryを持つため、queryは許可する。
     * 一方でscheme・port・userInfo・path traversalの制約は台帳URLと同じに保つ。
     */
    fun isAllowedRedirectTarget(url: String): Boolean {
        val uri = parse(url) ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return ALLOWED_REDIRECT_DOMAINS.any { domain -> host == domain || host.endsWith(".$domain") }
    }

    private fun parse(url: String): java.net.URI? =
        runCatching {
            val uri = java.net.URI(url)
            val valid =
                uri.scheme?.lowercase(Locale.ROOT) == "https" &&
                    uri.host != null &&
                    uri.userInfo == null &&
                    uri.port in ALLOWED_PORTS &&
                    !uri.rawPath.isNullOrBlank() &&
                    ".." !in uri.rawPath
            if (valid) uri else null
        }.getOrNull()

    private val ALLOWED_PORTS = setOf(-1, 443)
}

/**
 * 許可済みモデルの台帳。
 *
 * 追加・廃止はADRとdocs/LOCAL_LLM_MODELS.mdの更新を伴う。実行時に台帳へ
 * 項目を足す経路は存在しない。
 */
object LlmModelCatalog {
    /**
     * 既定で提示するモデル。空の場合、LLM経路は起動しない（fail-closed）。
     *
     * 登録条件と手順はdocs/LOCAL_LLM_MODELS.mdを正本とする。
     */
    val models: List<LlmModelDefinition> =
        listOf(
            LlmModelDefinition(
                id = "qwen3-0-6b-mixed-int4",
                version = "2026-08-01",
                displayName = "Qwen3 0.6B (mixed int4)",
                runtime = LlmRuntimeId.NATIVE,
                downloadUrl =
                    "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/" +
                        "qwen3_0_6b_mixed_int4.litertlm",
                fileName = "qwen3_0_6b_mixed_int4.litertlm",
                // Hugging Face APIのtree情報（size / lfs.oid）を2026-08-01に実測。
                sizeBytes = 497_664_000L,
                sha256 = "b1baab462f6be49d70eada79d715c2c52cd9ece0cad00bddf6a2c097d23498e9",
                licenseSpdxId = "Apache-2.0",
                licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
                sourceUrl = "https://huggingface.co/litert-community/Qwen3-0.6B",
                verifiedOn = "2026-08-01",
                // LiteRT-LM 0.15.0のAndroidManifestが宣言するminSdkと、本アプリが同梱するABI。
                minSdkInt = 24,
                requiredAbis = setOf("arm64-v8a"),
                // 暫定値。実機測定で確定するまでは保守的に倒す（docs/PERFORMANCE_BUDGETS.md）。
                minTotalRamBytes = 4L * 1024 * 1024 * 1024,
                // モデル本体に加えて検証中の一時ファイルを同時に置けるだけの空き。
                requiredFreeBytes = 1_100_000_000L,
                contextTokens = 8192,
                addedOn = "2026-08-01",
                knownLimitations =
                    listOf(
                        "日本語の回答品質について配布元の公式な保証はない",
                        "0.6Bの小型モデルのため、事実誤りや指示の取りこぼしが起こりやすい",
                        "推論速度・メモリ・発熱は実機測定で確定していない",
                    ),
            ),
        )

    val defaultModel: LlmModelDefinition? get() = models.firstOrNull { model -> model.retiredOn == null }

    fun findById(id: String): LlmModelDefinition? = models.firstOrNull { model -> model.id == id }
}
