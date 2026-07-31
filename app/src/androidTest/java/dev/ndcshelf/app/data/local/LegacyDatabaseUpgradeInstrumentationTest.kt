package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.1.2（Room v1）で作られた蔵書DBが、現行版のOS上で破壊されずに開けることを
 * 実機・エミュレーターのAndroid SQLiteで検証する。リリースゲートの
 * 「旧版から更新して既存蔵書が保持される」項目のうち、DB層の証跡を自動化する。
 * 端末側の更新インストール（署名・versionCode互換）は
 * `.github/workflows/android.yml` の update-install ジョブが担う。
 *
 * fixtureは匿名データだけを使用する。
 */
@RunWith(AndroidJUnit4::class)
class LegacyDatabaseUpgradeInstrumentationTest {
    @get:Rule
    val migrationHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    @Test
    fun libraryCreatedByV012SurvivesUpgradeOnDeviceSqlite() {
        migrationHelper.createDatabase(LEGACY_DATABASE, 1).apply {
            execSQL("INSERT INTO book_works VALUES ('work-1', '匿名サンプル図書A', 'サンプル著者A')")
            execSQL(
                """
                INSERT INTO book_editions VALUES (
                    'edition-1', 'work-1', '9784000000015', '匿名出版社', 2024,
                    'https://ndlsearch.ndl.go.jp/thumbnail/9784000000015.jpg',
                    '014.45', 'NDC10', 'NDL'
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO owned_copies VALUES (
                    'copy-1', 'edition-1', 'PHYSICAL', 'サンプル書斎・棚A', 'READING', 1700000000000
                )
                """.trimIndent(),
            )
            close()
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database =
            Room
                .databaseBuilder(context, AppDatabase::class.java, LEGACY_DATABASE)
                .addMigrations(*AppDatabase.MIGRATIONS.toTypedArray())
                .build()
        migrationHelper.closeWhenFinished(database)

        val row = runBlocking { database.libraryDao().findOwnedByCopyId("copy-1") }

        assertNotNull("v0.1.2で登録した所蔵コピーが失われている", row)
        val owned = requireNotNull(row)
        assertEquals("匿名サンプル図書A", owned.title)
        assertEquals("サンプル著者A", owned.primaryAuthor)
        assertEquals("9784000000015", owned.isbn13)
        assertEquals("匿名出版社", owned.publisher)
        assertEquals(2024, owned.publishedYear)
        assertEquals("014.45", owned.ndcCode)
        assertEquals("READING", owned.readingStatus)
        assertEquals(1_700_000_000_000L, owned.addedAt)
        // 旧版の自由記述の置き場所は文字列として残り、階層IDは未設定のままとする。
        assertEquals("サンプル書斎・棚A", owned.location)
        assertNull(owned.locationTierId)

        val version = database.openHelper.readableDatabase.version
        assertEquals(APP_DATABASE_VERSION, version)
    }

    private companion object {
        const val LEGACY_DATABASE = "legacy-v012-upgrade"
    }
}
