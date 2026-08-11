package dev.ndcshelf.app.ui.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.model.NdcCategory
import dev.ndcshelf.app.domain.model.ReadingSessionStatus
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.TagColorRole

/**
 * ドメインのenum・値オブジェクトを表示用の文字列リソースへ写像する。
 *
 * ドメイン側が保持する日本語の`label`は、自然言語検索のキーワード照合など
 * 言語依存の解析にそのまま使う。画面表示にはこちらの`labelRes`を使い、
 * `values/`（英語）と`values-ja/`（日本語）の両方へ追従させる。
 */
@get:StringRes
val ReadingStatus.labelRes: Int
    get() =
        when (this) {
            ReadingStatus.UNREAD -> R.string.reading_status_unread
            ReadingStatus.READING -> R.string.reading_status_reading
            ReadingStatus.READ -> R.string.reading_status_read
            ReadingStatus.PAUSED -> R.string.reading_status_paused
        }

@get:StringRes
val ReadingSessionStatus.labelRes: Int
    get() =
        when (this) {
            ReadingSessionStatus.READING -> R.string.reading_session_status_reading
            ReadingSessionStatus.PAUSED -> R.string.reading_session_status_paused
            ReadingSessionStatus.FINISHED -> R.string.reading_session_status_finished
        }

@get:StringRes
val TagColorRole.labelRes: Int
    get() =
        when (this) {
            TagColorRole.GRAY -> R.string.tag_color_gray
            TagColorRole.RED -> R.string.tag_color_red
            TagColorRole.ORANGE -> R.string.tag_color_orange
            TagColorRole.YELLOW -> R.string.tag_color_yellow
            TagColorRole.GREEN -> R.string.tag_color_green
            TagColorRole.TEAL -> R.string.tag_color_teal
            TagColorRole.BLUE -> R.string.tag_color_blue
            TagColorRole.PURPLE -> R.string.tag_color_purple
            TagColorRole.PINK -> R.string.tag_color_pink
            TagColorRole.BROWN -> R.string.tag_color_brown
        }

/**
 * NDC第1次区分（類）の表示名。分類記号そのもの（`digit`・NDCコード）は
 * 言語非依存の識別子なので翻訳せず、類名だけをロケールごとに切り替える。
 */
@StringRes
fun ndcCategoryLabelRes(digit: Int): Int =
    when (digit) {
        0 -> R.string.ndc_category_0
        1 -> R.string.ndc_category_1
        2 -> R.string.ndc_category_2
        3 -> R.string.ndc_category_3
        4 -> R.string.ndc_category_4
        5 -> R.string.ndc_category_5
        6 -> R.string.ndc_category_6
        7 -> R.string.ndc_category_7
        8 -> R.string.ndc_category_8
        else -> R.string.ndc_category_9
    }

@get:StringRes
val NdcCategory.labelRes: Int
    get() = ndcCategoryLabelRes(digit)

/**
 * 置き場所の保存値を表示用へ写像する。
 *
 * 保存値は端末ロケールに依存しない空文字（[dev.ndcshelf.app.domain.model.LibraryDefaults.UNSET_LOCATION]）。
 * 保存値をそのまま表示すると、v16以前に書き込まれた日本語literalが
 * 英語ロケールの画面へ出てしまう。
 */
@Composable
fun String.orUnsetLocationLabel(): String = ifBlank { stringResource(R.string.location_unset_value) }

/**
 * 所蔵ラベルの保存値を表示用へ写像する。
 *
 * 利用者が付けていない場合は空文字で保存し、表示のたびにlocalizeする。
 */
@Composable
fun String.orDefaultCopyLabel(): String = ifBlank { stringResource(R.string.copy_label_default) }
