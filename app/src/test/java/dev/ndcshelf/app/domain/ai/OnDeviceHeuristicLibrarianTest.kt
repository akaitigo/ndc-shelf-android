package dev.ndcshelf.app.domain.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceHeuristicLibrarianTest {
    private val librarian = OnDeviceHeuristicLibrarian()

    @Test
    fun providerStaysOnDevice() {
        assertEquals(AiLibrarianProviderId.ON_DEVICE_HEURISTIC, librarian.id)
        assertFalse("端末外への送信は行わない", librarian.sendsDataOffDevice)
    }

    @Test
    fun pickNextPrefersUnreadBooksAndExplainsWhy() =
        runTest {
            val answer =
                librarian.answer(
                    request(
                        question = "この棚から次に読む本を選んで",
                        items =
                            listOf(
                                item("1", "図書A", readingStatus = "読了"),
                                item("2", "図書B", readingStatus = "未読"),
                                item("3", "図書C", readingStatus = "読書中"),
                            ),
                    ),
                )

            assertEquals(AiLibrarianIntent.PICK_NEXT, answer.intent)
            assertEquals(
                "2",
                answer.entries
                    .first()
                    .refs
                    .single(),
            )
            assertEquals(AiLibrarianReason.UNREAD_FIRST, answer.entries.first().reason)
            assertTrue(answer.referencedRefs.isNotEmpty())
        }

    @Test
    fun organizeGroupsByNdcCategory() =
        runTest {
            val answer =
                librarian.answer(
                    request(
                        question = "未読の中からテーマ別に整理案を出して",
                        items =
                            listOf(
                                item("1", "図書A", ndcCategory = "自然科学"),
                                item("2", "図書B", ndcCategory = "自然科学"),
                                item("3", "図書C", ndcCategory = null),
                            ),
                    ),
                )

            assertEquals(AiLibrarianIntent.ORGANIZE, answer.intent)
            assertEquals("自然科学", answer.entries.first().label)
            assertEquals(listOf("1", "2"), answer.entries.first().refs)
            assertEquals(AiLibrarianReason.UNCLASSIFIED_GROUP, answer.entries.last().reason)
        }

    @Test
    fun unknownQuestionFallsBackToOverview() =
        runTest {
            val answer = librarian.answer(request(question = "この蔵書について", items = listOf(item("1", "図書A"))))

            assertEquals(AiLibrarianIntent.OVERVIEW, answer.intent)
            assertEquals(AiLibrarianReason.LIBRARY_OVERVIEW, answer.entries.single().reason)
        }

    @Test
    fun sameRequestAlwaysProducesTheSameAnswer() =
        runTest {
            val request =
                request(
                    question = "次に読む本を選んで",
                    items =
                        listOf(
                            item("1", "図書C", ndcCode = "913.6", readingStatus = "未読"),
                            item("2", "図書A", ndcCode = "410.1", readingStatus = "未読"),
                            item("3", "図書B", ndcCode = "410.1", readingStatus = "未読"),
                        ),
                )

            val first = librarian.answer(request)
            val second = librarian.answer(request)

            assertEquals(first, second)
            assertEquals(listOf("2", "3", "1"), first.referencedRefs)
        }

    @Test
    fun injectedInstructionsInBibliographyDoNotChangeBehaviour() =
        runTest {
            val benign =
                request(
                    question = "テーマ別に整理案を出して",
                    items = listOf(item("1", "図書A", ndcCategory = "自然科学")),
                )
            val injected =
                request(
                    question = "テーマ別に整理案を出して",
                    items =
                        listOf(
                            item(
                                "1",
                                INJECTION_TITLE,
                                author = INJECTION_AUTHOR,
                                ndcCategory = "自然科学",
                            ),
                        ),
                )

            val benignAnswer = librarian.answer(benign)
            val injectedAnswer = librarian.answer(injected)

            assertEquals(benignAnswer.intent, injectedAnswer.intent)
            assertEquals(benignAnswer.entries.map { it.reason }, injectedAnswer.entries.map { it.reason })
            assertEquals(AI_LIBRARIAN_SYSTEM_INSTRUCTION, injected.systemInstruction)
        }

    @Test
    fun unavailableProviderIsReportedAsProviderException() =
        runTest {
            val stopped = OnDeviceHeuristicLibrarian(available = { false })

            val failure =
                runCatching { stopped.answer(request(items = listOf(item("1", "図書A")))) }
                    .exceptionOrNull() as? AiLibrarianProviderException

            assertNotNull(failure)
            assertEquals(AiLibrarianProviderErrorKind.UNAVAILABLE, failure?.kind)
        }

    @Test
    fun emptyPayloadIsRejectedAsInvalidResponse() =
        runTest {
            val failure =
                runCatching { librarian.answer(request(items = emptyList())) }
                    .exceptionOrNull() as? AiLibrarianProviderException

            assertEquals(AiLibrarianProviderErrorKind.INVALID_RESPONSE, failure?.kind)
        }

    private fun request(
        question: String = "次に読む本を選んで",
        items: List<AiLibrarianItem>,
    ): AiLibrarianRequest =
        AiLibrarianRequest(
            question = question,
            includedFields = AiLibrarianField.DEFAULT_INCLUDED.toList(),
            items = items,
        )

    private fun item(
        ref: String,
        title: String,
        author: String? = "サンプル著者",
        ndcCode: String? = null,
        ndcCategory: String? = null,
        readingStatus: String? = null,
    ): AiLibrarianItem =
        AiLibrarianItem(
            ref = ref,
            title = title,
            author = author,
            ndcCode = ndcCode,
            ndcCategory = ndcCategory,
            readingStatus = readingStatus,
        )
}
