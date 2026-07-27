package dev.ndcshelf.app.data.repository

import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.SeriesVolumeRow
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.SeriesOverview
import dev.ndcshelf.app.domain.model.SeriesVolume
import dev.ndcshelf.app.domain.repository.SeriesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class RoomSeriesRepository(database: AppDatabase) : SeriesRepository {
    private val dao = database.seriesDao()

    override fun observeCatalog(): Flow<List<SeriesOverview>> = combine(
        dao.observeSeries(),
        dao.observeAllVolumes(),
    ) { series, rows ->
        val volumesBySeries = rows.map(SeriesVolumeRow::toVolume).groupBy { it.membership.seriesId }
        series.map { item ->
            SeriesOverview(
                series = item.toDomain(),
                volumes = volumesBySeries[item.id].orEmpty(),
            )
        }
    }
}

internal fun SeriesVolumeRow.toVolume(): SeriesVolume = SeriesVolume(
    membership = toMembershipDomain(),
    ownedEditionId = ownedEditionId,
    bookstoreIsbn = bookstoreIsbn,
    ownedCopyCount = ownedCopyCount,
    readCopyCount = readCopyCount,
    readingCopyCount = readingCopyCount,
    purchaseStatus = when (purchaseStatusRank) {
        2 -> PurchaseStatus.RESERVED
        1 -> PurchaseStatus.WANTED
        else -> null
    },
    latestOwnedAddedAt = latestOwnedAddedAt,
)

private fun SeriesVolumeRow.toMembershipDomain() =
    dev.ndcshelf.app.domain.model.SeriesMembership(
        id = membershipId,
        seriesId = seriesId,
        workId = workId,
        workTitle = workTitle,
        primaryAuthor = primaryAuthor,
        sortOrderKey = sortOrderKey,
        volumeLabel = volumeLabel,
        type = enumValueOf(type),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
