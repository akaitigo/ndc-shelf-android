package dev.ndcshelf.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.repository.FractionalOrderKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesDaoIntegrationTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: SeriesDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.seriesDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun membershipsUseExplicitOrderForIntegerDecimalPartsAndSideStories() = runBlocking {
        val labels = listOf("1巻", "1.5巻", "前編", "後編", "上巻", "下巻", "外伝")
        database.libraryDao().upsertWorks(
            labels.indices.map { index -> BookWorkEntity("work-$index", labels[index], "著者") },
        )
        dao.upsertSeries(SeriesEntity("series-1", "長編", 1, 1))
        var left: String? = null
        labels.forEachIndexed { index, label ->
            val key = FractionalOrderKey.between(left, null, "membership-$index")
            dao.insertMembership(
                SeriesMembershipEntity(
                    id = "membership-$index",
                    seriesId = "series-1",
                    workId = "work-$index",
                    sortOrderKey = key,
                    volumeLabel = label,
                    type = if (label == "外伝") "SIDE_STORY" else "MAIN_STORY",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            left = key
        }

        assertEquals(labels, dao.observeMemberships("series-1").first().map { it.volumeLabel })
    }

    @Test
    fun workCanBelongToMultipleSeriesButCannotRepeatWithinOneSeries() {
        runBlocking {
            database.libraryDao().upsertWorks(listOf(BookWorkEntity("work-1", "交差作品", "著者")))
            dao.upsertSeries(SeriesEntity("series-a", "本編", 1, 1))
            dao.upsertSeries(SeriesEntity("series-b", "共有世界", 1, 1))
            dao.insertMembership(membership("membership-a", "series-a", "40"))
            dao.insertMembership(membership("membership-b", "series-b", "40"))

            assertEquals(2, dao.findMembershipsForWork("work-1").size)
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { dao.insertMembership(membership("membership-c", "series-a", "80")) }
            }
        }
    }

    @Test
    fun deletingWorkOrSeriesCascadesOnlyItsMemberships() = runBlocking {
        database.libraryDao().upsertWorks(
            listOf(
                BookWorkEntity("work-1", "第一巻", "著者"),
                BookWorkEntity("work-2", "第二巻", "著者"),
            ),
        )
        dao.upsertSeries(SeriesEntity("series-1", "本編", 1, 1))
        dao.insertMembership(membership("membership-1", "series-1", "40", "work-1"))
        dao.insertMembership(membership("membership-2", "series-1", "80", "work-2"))

        database.libraryDao().deleteWorkById("work-1")
        assertEquals(listOf("work-2"), dao.observeMemberships("series-1").first().map { it.workId })

        dao.deleteSeries("series-1")
        assertEquals(emptyList<SeriesMembershipEntity>(), dao.getAllMemberships())
        assertEquals(1, database.libraryDao().getAllWorks().size)
    }

    @Test
    fun movingMembershipToMergedSeriesPreservesStableId() = runBlocking {
        database.libraryDao().upsertWorks(listOf(BookWorkEntity("work-1", "第一巻", "著者")))
        dao.upsertSeries(SeriesEntity("source", "旧シリーズ", 1, 1))
        dao.upsertSeries(SeriesEntity("target", "統合先", 1, 1))
        dao.insertMembership(membership("membership-1", "source", "40"))

        assertEquals(
            1,
            dao.updateMembership(
                membershipId = "membership-1",
                seriesId = "target",
                sortOrderKey = "80",
                volumeLabel = "上巻",
                type = "OMNIBUS",
                updatedAt = 2,
            ),
        )

        val moved = dao.observeMemberships("target").first().single()
        assertEquals("membership-1", moved.membershipId)
        assertEquals("target", moved.seriesId)
        assertEquals("上巻", moved.volumeLabel)
        assertEquals("OMNIBUS", moved.type)
    }

    private fun membership(
        id: String,
        seriesId: String,
        key: String,
        workId: String = "work-1",
    ) = SeriesMembershipEntity(
        id = id,
        seriesId = seriesId,
        workId = workId,
        sortOrderKey = key,
        volumeLabel = "1巻",
        type = "MAIN_STORY",
        createdAt = 1,
        updatedAt = 1,
    )
}
