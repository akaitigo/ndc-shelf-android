package dev.ndcshelf.app.domain.text

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * ロケール解決を表示直前まで遅らせるメッセージ。
 *
 * ドメイン・データ層は文字列そのものではなくリソースIDと引数だけを返し、
 * 実際の文言は`values/`（英語）と`values-ja/`（日本語）から解決する。
 * こうすることで、端末の言語を切り替えたときに検証エラーや同期の受領メッセージも
 * 同じロケールで表示される。
 *
 * [args]に[UiMessage]を入れると入れ子で解決する（例: 「NDC 9類 文学」）。
 */
data class UiMessage(
    @param:StringRes val resId: Int,
    val args: List<Any> = emptyList(),
) {
    constructor(
        @StringRes resId: Int,
        vararg args: Any,
    ) : this(resId, args.toList())

    fun resolve(resources: Resources): String =
        if (args.isEmpty()) {
            resources.getString(resId)
        } else {
            resources.getString(
                resId,
                *args.map { if (it is UiMessage) it.resolve(resources) else it }.toTypedArray(),
            )
        }

    fun resolve(context: Context): String = resolve(context.resources)
}
