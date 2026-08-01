package dev.ndcshelf.app.domain.model

import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.text.UiMessage

/**
 * 蔵書を横断整理するタグ。作品（BookWork）単位で付与する。
 *
 * - 手動コレクションは独立モデルを持たず、タグそのもので表現する（設計判断はdocs/ARCHITECTURE.md）。
 * - 識別子は端末間で衝突しない独立UUIDで、同期時にもこのIDで識別する。
 * - タグ名は信頼できない入力として扱い、表示はComposeのTextでプレーンテキストとして
 *   のみ描画する（方針はdocs/ARCHITECTURE.md「タグとコレクション」）。
 */
data class Tag(
    val id: String,
    val name: String,
    val colorRole: TagColorRole,
    val createdAt: Long,
    val updatedAt: Long,
)

data class TagWithUsage(
    val tag: Tag,
    val taggedWorkCount: Int,
)

data class TagAssignment(
    val id: String,
    val tagId: String,
    val workId: String,
    val createdAt: Long,
)

/**
 * 固定パレットの色ロール。色名に依存しないよう、UIでは必ずラベルのテキストを併記する。
 */
enum class TagColorRole {
    GRAY,
    RED,
    ORANGE,
    YELLOW,
    GREEN,
    TEAL,
    BLUE,
    PURPLE,
    PINK,
    BROWN,
}

/** 保存済み検索（検索条件コレクション）。検索条件そのものを保存する。 */
data class SavedSearch(
    val id: String,
    val name: String,
    val criteria: LibrarySearchCriteria,
    val createdAt: Long,
    val updatedAt: Long,
)

sealed interface TagNameValidation {
    data class Valid(
        val normalized: String,
    ) : TagNameValidation

    data class Invalid(
        val reason: UiMessage,
    ) : TagNameValidation
}

/**
 * タグ名・コレクション名の検証。前後空白の除去と連続空白の1つへの正規化を行い、
 * 空・長さ超過・制御文字を拒否する。重複と件数上限はRepositoryで検査する。
 */
object TagNameRules {
    const val MAX_NAME_LENGTH = 50
    const val MAX_TAGS = 100
    const val MAX_SAVED_SEARCHES = 50
    const val MAX_TAG_FILTERS = 10

    fun validate(rawName: String): TagNameValidation {
        val normalized = rawName.trim().replace(WHITESPACE_RUN, " ")
        return when {
            normalized.isEmpty() -> {
                TagNameValidation.Invalid(UiMessage(R.string.validation_name_required))
            }

            normalized.length > MAX_NAME_LENGTH -> {
                TagNameValidation.Invalid(
                    UiMessage(R.string.validation_name_max_length, MAX_NAME_LENGTH),
                )
            }

            normalized.any { it.isISOControl() } -> {
                TagNameValidation.Invalid(UiMessage(R.string.validation_no_control_chars))
            }

            else -> {
                TagNameValidation.Valid(normalized)
            }
        }
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}
