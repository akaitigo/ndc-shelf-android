package dev.ndcshelf.app.data.repository

import dev.ndcshelf.app.data.local.SeriesEntity
import dev.ndcshelf.app.data.local.SeriesMembershipRow
import dev.ndcshelf.app.domain.model.BookSeries
import dev.ndcshelf.app.domain.model.SeriesMembership
import dev.ndcshelf.app.domain.model.SeriesMembershipType
import dev.ndcshelf.app.domain.model.SeriesMembershipOrigin
import dev.ndcshelf.app.domain.model.SeriesMembershipConfirmer

internal fun SeriesEntity.toDomain(): BookSeries = BookSeries(
    id = id,
    name = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun SeriesMembershipRow.toDomain(): SeriesMembership = SeriesMembership(
    id = membershipId,
    seriesId = seriesId,
    workId = workId,
    workTitle = workTitle,
    primaryAuthor = primaryAuthor,
    sortOrderKey = sortOrderKey,
    volumeLabel = volumeLabel,
    type = SeriesMembershipType.valueOf(type),
    createdAt = createdAt,
    updatedAt = updatedAt,
    origin = SeriesMembershipOrigin.valueOf(origin),
    confirmedBy = SeriesMembershipConfirmer.valueOf(confirmedBy),
    sourceTitle = sourceTitle,
)
