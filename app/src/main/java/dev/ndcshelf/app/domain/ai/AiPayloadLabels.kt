package dev.ndcshelf.app.domain.ai

import dev.ndcshelf.app.domain.model.ReadingStatus

/**
 * AI司書へ渡すペイロード上の読書状態ラベル。
 *
 * 画面表示用のラベル（`dev.ndcshelf.app.ui.text.labelRes`）とは意図的に分けている。
 * AI司書のシステムプロンプトと意図判定キーワードは日本語固定のプロトコルであり、
 * ペイロードの語彙が端末ロケールで揺れると[OnDeviceHeuristicLibrarian]の
 * 読書状態判定が成立しなくなるため、ここは翻訳しない。
 * 詳細は docs/I18N.md の「翻訳しない文字列」を参照。
 */
internal object AiPayloadLabels {
    const val UNREAD = "未読"
    const val READING = "読書中"
    const val PAUSED = "中断"
    const val READ = "読了"

    /** 書名が空・サニタイズで消えた場合のプレースホルダ。 */
    const val UNTITLED = "（書名なし）"
}

internal fun ReadingStatus.aiPayloadLabel(): String =
    when (this) {
        ReadingStatus.UNREAD -> AiPayloadLabels.UNREAD
        ReadingStatus.READING -> AiPayloadLabels.READING
        ReadingStatus.READ -> AiPayloadLabels.READ
        ReadingStatus.PAUSED -> AiPayloadLabels.PAUSED
    }
