package dev.ndcshelf.app.domain.ai

/**
 * 端末内で完結する決定的なAI司書プロバイダ。
 *
 * ネットワーク通信・乱数・時刻を一切使わず、同じ[AiLibrarianRequest]からは常に
 * 同じ回答を返す。クラウドのAIプロバイダは未接続で、接続機能は料金と送信内容を
 * 明示したうえで将来の版に追加する（docs/adr/0007-optin-ai-librarian.md）。
 *
 * 意図の判定は利用者自身の[AiLibrarianRequest.question]だけを見る。
 * [AiLibrarianItem]の文字列は分類・整列の値としてのみ扱い、制御に使わないため、
 * 書誌タイトルへ命令文を仕込んでも動作を変えられない。
 */
class OnDeviceHeuristicLibrarian(
    private val available: () -> Boolean = { true },
) : AiLibrarianProvider {
    override val id: AiLibrarianProviderId = AiLibrarianProviderId.ON_DEVICE_HEURISTIC

    override val sendsDataOffDevice: Boolean = false

    override suspend fun answer(request: AiLibrarianRequest): AiLibrarianAnswer {
        if (!available()) {
            throw AiLibrarianProviderException(AiLibrarianProviderErrorKind.UNAVAILABLE)
        }
        if (request.items.isEmpty()) {
            throw AiLibrarianProviderException(AiLibrarianProviderErrorKind.INVALID_RESPONSE)
        }
        return when (detectIntent(request.question)) {
            AiLibrarianIntent.PICK_NEXT -> pickNext(request.items)
            AiLibrarianIntent.ORGANIZE -> organize(request.items)
            AiLibrarianIntent.OVERVIEW -> overview(request.items)
        }
    }

    private fun detectIntent(question: String): AiLibrarianIntent =
        when {
            PICK_NEXT_KEYWORDS.any { keyword -> question.contains(keyword) } -> {
                AiLibrarianIntent.PICK_NEXT
            }

            ORGANIZE_KEYWORDS.any { keyword -> question.contains(keyword) } -> {
                AiLibrarianIntent.ORGANIZE
            }

            else -> {
                AiLibrarianIntent.OVERVIEW
            }
        }

    private fun pickNext(items: List<AiLibrarianItem>): AiLibrarianAnswer {
        val ordered =
            items.sortedWith(
                compareBy(
                    { item -> statusRank(item.readingStatus) },
                    { item -> item.ndcCode ?: UNCLASSIFIED_SORT_KEY },
                    { item -> item.title },
                    { item -> item.ref.toIntOrNull() ?: Int.MAX_VALUE },
                ),
            )
        val entries =
            ordered.take(PICK_NEXT_COUNT).map { item ->
                AiLibrarianAnswerEntry(
                    label = item.ndcCategory,
                    reason =
                        when {
                            item.readingStatus == AiPayloadLabels.UNREAD -> AiLibrarianReason.UNREAD_FIRST
                            item.ndcCategory != null -> AiLibrarianReason.CATEGORY_MATCH
                            else -> AiLibrarianReason.BIBLIOGRAPHIC_ORDER
                        },
                    refs = listOf(item.ref),
                )
            }
        return AiLibrarianAnswer(intent = AiLibrarianIntent.PICK_NEXT, entries = entries)
    }

    private fun organize(items: List<AiLibrarianItem>): AiLibrarianAnswer {
        val groups =
            items
                .groupBy { item -> item.ndcCategory }
                .entries
                .sortedWith(
                    compareBy(
                        { group -> -group.value.size },
                        { group -> group.key ?: UNCLASSIFIED_SORT_KEY },
                    ),
                ).take(MAX_GROUPS)
        val entries =
            groups.map { group ->
                AiLibrarianAnswerEntry(
                    label = group.key,
                    reason =
                        if (group.key == null) {
                            AiLibrarianReason.UNCLASSIFIED_GROUP
                        } else {
                            AiLibrarianReason.CATEGORY_GROUP
                        },
                    refs =
                        group.value
                            .sortedBy(AiLibrarianItem::title)
                            .take(MAX_REFS_PER_GROUP)
                            .map(AiLibrarianItem::ref),
                )
            }
        return AiLibrarianAnswer(intent = AiLibrarianIntent.ORGANIZE, entries = entries)
    }

    private fun overview(items: List<AiLibrarianItem>): AiLibrarianAnswer =
        AiLibrarianAnswer(
            intent = AiLibrarianIntent.OVERVIEW,
            entries =
                listOf(
                    AiLibrarianAnswerEntry(
                        label = null,
                        reason = AiLibrarianReason.LIBRARY_OVERVIEW,
                        refs =
                            items
                                .sortedBy(AiLibrarianItem::title)
                                .take(MAX_OVERVIEW_REFS)
                                .map(AiLibrarianItem::ref),
                    ),
                ),
        )

    private fun statusRank(readingStatus: String?): Int =
        when (readingStatus) {
            AiPayloadLabels.UNREAD -> 0
            null -> 1
            AiPayloadLabels.PAUSED -> 2
            AiPayloadLabels.READING -> 3
            else -> 4
        }

    private companion object {
        const val PICK_NEXT_COUNT = 3
        const val MAX_GROUPS = 5
        const val MAX_REFS_PER_GROUP = 8
        const val MAX_OVERVIEW_REFS = 10

        /** 並べ替えで分類なしを末尾へ送るための番兵。NDC記号より必ず大きい。 */
        const val UNCLASSIFIED_SORT_KEY = "￿"

        val PICK_NEXT_KEYWORDS =
            listOf("次に読", "次の一冊", "何を読", "どれを読", "選んで", "選ぶ", "おすすめ", "オススメ")

        val ORGANIZE_KEYWORDS =
            listOf("整理", "分類", "テーマ", "まとめ", "グループ", "並べ")
    }
}
