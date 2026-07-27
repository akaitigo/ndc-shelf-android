package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibrarySort
import dev.ndcshelf.app.domain.model.ReadingStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPreferencesLibrarySearchSettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences("library-search-settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun searchCriteriaSurvivesStoreRecreationWithoutTransientEdition() {
        SharedPreferencesLibrarySearchSettingsStore(context).save(
            LibrarySearchCriteria(
                query = "郷土資料",
                readingStatus = ReadingStatus.READING,
                sort = LibrarySort.SHELF,
                selectedEditionId = "transient-edition",
            ),
        )

        assertEquals(
            LibrarySearchCriteria(
                query = "郷土資料",
                readingStatus = ReadingStatus.READING,
                sort = LibrarySort.SHELF,
            ),
            SharedPreferencesLibrarySearchSettingsStore(context).load(),
        )
    }
}
