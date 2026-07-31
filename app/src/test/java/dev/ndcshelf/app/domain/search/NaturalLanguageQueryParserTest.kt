package dev.ndcshelf.app.domain.search

import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.Tag
import dev.ndcshelf.app.domain.model.TagColorRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.util.TimeZone

/**
 * fixtures/nl_search_queries.json のテーブル駆動テスト。
 * 対応する検索意図（読書状態・NDC類・場所・追加時期・タグ・複合）と、
 * 非対応例（主観条件）の通常検索へのフォールバックを日本語クエリで検証する。
 */
class NaturalLanguageQueryParserTest {
    private val fixture: NlSearchFixture =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE_PATH)) {
            "fixtureが見つかりません: $FIXTURE_PATH"
        }.use { stream ->
            FIXTURE_JSON.decodeFromString(
                NlSearchFixture.serializer(),
                stream.readBytes().decodeToString(),
            )
        }

    private val fixtureTags: List<Tag> =
        fixture.tags.map { tag ->
            Tag(id = tag.id, name = tag.name, colorRole = TagColorRole.GRAY, createdAt = 0L, updatedAt = 0L)
        }

    private val nowMillis: Long = fixture.nowIso.toEpochMillis()
    private val timeZone: TimeZone = TimeZone.getTimeZone(fixture.timeZone)

    @Test
    fun fixtureCasesProduceExpectedInterpretation() {
        assertTrue("fixtureにケースがありません", fixture.cases.isNotEmpty())
        val failures = mutableListOf<String>()
        fixture.cases.forEach { case ->
            val interpretation =
                NaturalLanguageQueryParser.parse(
                    query = case.query,
                    tags = fixtureTags,
                    nowMillis = nowMillis,
                    timeZone = timeZone,
                )
            val base = LibrarySearchCriteria(query = case.query)
            val effective = base.applyInterpretation(interpretation)
            val expected = case.expected
            val problems = mutableListOf<String>()
            if (expected.interpreted != interpretation.chips.isNotEmpty()) {
                problems += "interpreted=${interpretation.chips.isNotEmpty()} (期待: ${expected.interpreted})"
            }
            if (expected.interpreted) {
                val expectedStatus = expected.readingStatus?.let(ReadingStatus::valueOf)
                if (effective.readingStatus != expectedStatus) {
                    problems += "readingStatus=${effective.readingStatus} (期待: $expectedStatus)"
                }
                if (effective.ndcTopClass != expected.ndcTopClass) {
                    problems += "ndcTopClass=${effective.ndcTopClass} (期待: ${expected.ndcTopClass})"
                }
                if (effective.locationQuery != expected.locationQuery) {
                    problems += "locationQuery=${effective.locationQuery} (期待: ${expected.locationQuery})"
                }
                if (effective.addedAfterMillis != expected.addedAfterIso?.toEpochMillis()) {
                    problems += "addedAfterMillis=${effective.addedAfterMillis} " +
                        "(期待: ${expected.addedAfterIso?.toEpochMillis()})"
                }
                if (effective.addedBeforeMillis != expected.addedBeforeIso?.toEpochMillis()) {
                    problems += "addedBeforeMillis=${effective.addedBeforeMillis} " +
                        "(期待: ${expected.addedBeforeIso?.toEpochMillis()})"
                }
                if (effective.tagIds != expected.tagIds.toSet()) {
                    problems += "tagIds=${effective.tagIds} (期待: ${expected.tagIds.toSet()})"
                }
                expected.residualQuery?.let { residual ->
                    if (effective.query != residual) {
                        problems += "residualQuery=「${effective.query}」 (期待: 「$residual」)"
                    }
                }
            } else if (effective != base) {
                // 解釈不能クエリは条件を一切変更せず、従来の部分一致検索のまま。
                problems += "フォールバック時に条件が変化: $effective"
            }
            if (problems.isNotEmpty()) {
                failures += "[${case.name}] query=「${case.query}」: ${problems.joinToString("; ")}"
            }
        }
        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    @Test
    fun uninterpretedQueryReturnsSameCriteriaInstance() {
        val criteria = LibrarySearchCriteria(query = "面白い本")
        val interpretation =
            NaturalLanguageQueryParser.parse("面白い本", emptyList(), nowMillis, timeZone)
        assertSame(criteria, criteria.applyInterpretation(interpretation))
    }

    @Test
    fun dismissedChipRestoresItsTokenToPlainTextSearch() {
        val interpretation =
            NaturalLanguageQueryParser.parse("未読の自然科学", emptyList(), nowMillis, timeZone)
        val statusChipId =
            interpretation.chips
                .filterIsInstance<SearchInterpretationChip.Status>()
                .single()
                .id

        val effective =
            LibrarySearchCriteria(query = "未読の自然科学")
                .applyInterpretation(interpretation, dismissedChipIds = setOf(statusChipId))

        assertEquals(null, effective.readingStatus)
        assertEquals(4, effective.ndcTopClass)
        assertEquals("未読", effective.query)
    }

    @Test
    fun dismissingEveryChipRestoresOriginalPlainTextQuery() {
        val query = "未読の自然科学"
        val interpretation = NaturalLanguageQueryParser.parse(query, emptyList(), nowMillis, timeZone)
        val allChipIds = interpretation.chips.map(SearchInterpretationChip::id).toSet()

        val effective =
            LibrarySearchCriteria(query = query)
                .applyInterpretation(interpretation, dismissedChipIds = allChipIds)

        assertEquals(query, effective.query)
        assertEquals(null, effective.readingStatus)
        assertEquals(null, effective.ndcTopClass)
    }

    @Test
    fun singleNdcLabelQueryCanBeDismissedBackToTitleSearch() {
        // 「歴史」というタイトルを探したい利用者が、NDC解釈を解除して通常検索へ戻せること。
        val interpretation = NaturalLanguageQueryParser.parse("歴史", emptyList(), nowMillis, timeZone)
        val ndcChip = interpretation.chips.single()
        assertTrue(ndcChip is SearchInterpretationChip.Ndc)

        val effective =
            LibrarySearchCriteria(query = "歴史")
                .applyInterpretation(interpretation, dismissedChipIds = setOf(ndcChip.id))

        assertEquals("歴史", effective.query)
        assertEquals(null, effective.ndcTopClass)
    }

    @Test
    fun chipsAreOrderedByPositionInQuery() {
        val interpretation =
            NaturalLanguageQueryParser.parse("去年買った未読の文学", emptyList(), nowMillis, timeZone)
        assertEquals(
            listOf(
                SearchInterpretationChip.Added::class,
                SearchInterpretationChip.Status::class,
                SearchInterpretationChip.Ndc::class,
            ),
            interpretation.chips.map { chip -> chip::class },
        )
    }

    @Test
    fun chipLabelsStayPlainTextForScreenReaders() {
        val tags = listOf(Tag("tag-1", "研究用", TagColorRole.BLUE, 0L, 0L))
        val interpretation =
            NaturalLanguageQueryParser.parse("書斎にある去年買った未読の自然科学 #研究用", tags, nowMillis, timeZone)
        val labels = interpretation.chips.map(SearchInterpretationChip::label)
        assertEquals(
            listOf("場所: 書斎", "追加: 去年", "未読", "NDC 4類 自然科学", "タグ: 研究用"),
            labels,
        )
    }

    private fun String.toEpochMillis(): Long = OffsetDateTime.parse(this).toInstant().toEpochMilli()

    @Serializable
    private data class NlSearchFixture(
        val description: String = "",
        val nowIso: String,
        val timeZone: String,
        val tags: List<FixtureTag> = emptyList(),
        val cases: List<FixtureCase>,
    )

    @Serializable
    private data class FixtureTag(
        val id: String,
        val name: String,
    )

    @Serializable
    private data class FixtureCase(
        val name: String,
        val query: String,
        val expected: FixtureExpectation,
    )

    @Serializable
    private data class FixtureExpectation(
        val interpreted: Boolean,
        val readingStatus: String? = null,
        val ndcTopClass: Int? = null,
        val locationQuery: String? = null,
        val addedAfterIso: String? = null,
        val addedBeforeIso: String? = null,
        val tagIds: List<String> = emptyList(),
        val residualQuery: String? = null,
    )

    private companion object {
        const val FIXTURE_PATH = "fixtures/nl_search_queries.json"
        val FIXTURE_JSON = Json { ignoreUnknownKeys = true }
    }
}
