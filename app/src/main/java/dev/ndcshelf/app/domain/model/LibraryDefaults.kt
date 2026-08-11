package dev.ndcshelf.app.domain.model

/**
 * 端末のロケールに依存しない「未設定」の表し方。
 *
 * v0.6.0までは置き場所の未設定を日本語literalの`"未設定"`、所蔵ラベルの既定を
 * `"所蔵本"`としてデータベースへ書き込んでいた。保存値をそのまま画面へ出していたため、
 * 英語ロケールの利用者には日本語が残り、さらに`LibraryScreen`が
 * `stringResource(R.string.location_unset_value)`を書き込んでいたので、
 * **同じ「未設定」が端末のロケール次第で`"未設定"`にも`"Not set"`にもなっていた**。
 *
 * 保存値は常に空文字とし、表示側でその都度ローカライズする。空文字にすることで
 * 「利用者が何も入力していない」ことを言語に依存せずに表せる。
 */
object LibraryDefaults {
    /** 置き場所が未設定であることを表す保存値。表示は`R.string.location_unset_value`。 */
    const val UNSET_LOCATION: String = ""

    /** 所蔵ラベルが未設定であることを表す保存値。表示は`R.string.copy_label_default`。 */
    const val UNSET_COPY_LABEL: String = ""

    /**
     * v17より前に書き込まれた置き場所の未設定値。
     *
     * `"Not set"`は英語ロケールの端末で`location_unset_value`がそのまま保存された分。
     * import・restoreでも旧データを受け取りうるため、移行SQLだけでなくコードからも参照する。
     */
    val LEGACY_UNSET_LOCATIONS: Set<String> = setOf("未設定", "Not set")

    /** v17より前に書き込まれた所蔵ラベルの既定値。 */
    val LEGACY_DEFAULT_COPY_LABELS: Set<String> = setOf("所蔵本")

    /** 保存値を正規化する。旧既定値と空白だけの入力は「未設定」へ寄せる。 */
    fun normalizeLocation(raw: String?): String =
        raw?.trim().orEmpty().let { value ->
            if (value in LEGACY_UNSET_LOCATIONS) UNSET_LOCATION else value
        }

    /** 保存値を正規化する。旧既定値と空白だけの入力は「未設定」へ寄せる。 */
    fun normalizeCopyLabel(raw: String?): String =
        raw?.trim().orEmpty().let { value ->
            if (value in LEGACY_DEFAULT_COPY_LABELS) UNSET_COPY_LABEL else value
        }
}
