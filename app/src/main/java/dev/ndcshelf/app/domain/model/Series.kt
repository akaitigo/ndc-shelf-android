package dev.ndcshelf.app.domain.model

data class BookSeries(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class SeriesMembershipOrigin {
    TITLE_SUGGESTION,
    MANUAL,
}

enum class SeriesMembershipConfirmer {
    USER,
}

data class SeriesMembership(
    val id: String,
    val seriesId: String,
    val workId: String,
    val workTitle: String,
    val primaryAuthor: String,
    val sortOrderKey: String,
    val volumeLabel: String,
    val type: SeriesMembershipType,
    val createdAt: Long,
    val updatedAt: Long,
    val origin: SeriesMembershipOrigin = SeriesMembershipOrigin.MANUAL,
    val confirmedBy: SeriesMembershipConfirmer = SeriesMembershipConfirmer.USER,
    val sourceTitle: String = "",
)

enum class SeriesMembershipType {
    MAIN_STORY,
    SIDE_STORY,
    OMNIBUS,
    OTHER,
}

data class SeriesVolume(
    val membership: SeriesMembership,
    val ownedEditionId: String?,
    val bookstoreIsbn: String?,
    val ownedCopyCount: Int,
    val readCopyCount: Int,
    val readingCopyCount: Int,
    val purchaseStatus: PurchaseStatus?,
    val latestOwnedAddedAt: Long?,
) {
    val state: SeriesVolumeState
        get() = when {
            ownedCopyCount > 0 -> SeriesVolumeState.OWNED
            purchaseStatus == PurchaseStatus.RESERVED -> SeriesVolumeState.RESERVED
            purchaseStatus == PurchaseStatus.WANTED -> SeriesVolumeState.WANTED
            else -> SeriesVolumeState.UNOWNED
        }

    val isRead: Boolean get() = readCopyCount > 0

    val isMissingCandidate: Boolean
        get() = membership.type == SeriesMembershipType.MAIN_STORY &&
            membership.volumeLabel.isExplicitSeriesSequence() &&
            ownedCopyCount == 0
}

data class SeriesOverview(
    val series: BookSeries,
    val volumes: List<SeriesVolume>,
) {
    val lastConfirmedAt: Long
        get() = maxOf(series.updatedAt, volumes.maxOfOrNull { it.membership.updatedAt } ?: Long.MIN_VALUE)
    val knownVolumeCount: Int get() = volumes.size
    val ownedVolumeCount: Int get() = volumes.count { it.ownedCopyCount > 0 }
    val readVolumeCount: Int get() = volumes.count(SeriesVolume::isRead)
    val missingCandidateCount: Int get() = volumes.count(SeriesVolume::isMissingCandidate)
    val latestOwnedVolume: SeriesVolume? get() = volumes.lastOrNull { it.ownedCopyCount > 0 }
    val completionRelevantCount: Int get() = volumes.count { volume ->
        volume.membership.type == SeriesMembershipType.MAIN_STORY &&
            volume.membership.volumeLabel.isExplicitSeriesSequence()
    }
    val isConfirmedMainStoryComplete: Boolean
        get() = completionRelevantCount > 0 &&
            volumes.filter { volume ->
                volume.membership.type == SeriesMembershipType.MAIN_STORY &&
                    volume.membership.volumeLabel.isExplicitSeriesSequence()
            }.all { it.ownedCopyCount > 0 }
}

enum class SeriesVolumeState {
    OWNED,
    WANTED,
    RESERVED,
    UNOWNED,
}

private val EXPLICIT_SEQUENCE_PATTERN = Regex(
    "^(?:第?[0-9０-９]+(?:[.．][0-9０-９]+)?(?:巻)?|[上下中](?:巻)?|前編|後編)$",
)

internal fun String.isExplicitSeriesSequence(): Boolean =
    EXPLICIT_SEQUENCE_PATTERN.matches(trim())
