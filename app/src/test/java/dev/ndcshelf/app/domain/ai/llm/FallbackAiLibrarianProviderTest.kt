package dev.ndcshelf.app.domain.ai.llm

import dev.ndcshelf.app.domain.ai.AiLibrarianAnswer
import dev.ndcshelf.app.domain.ai.AiLibrarianAnswerSource
import dev.ndcshelf.app.domain.ai.AiLibrarianField
import dev.ndcshelf.app.domain.ai.AiLibrarianItem
import dev.ndcshelf.app.domain.ai.AiLibrarianProvider
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderErrorKind
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderException
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderId
import dev.ndcshelf.app.domain.ai.AiLibrarianRequest
import dev.ndcshelf.app.domain.ai.OnDeviceHeuristicLibrarian
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FallbackAiLibrarianProviderTest {
    private val heuristic = OnDeviceHeuristicLibrarian()

    @Test
    fun `llm answer is used when the preferred provider succeeds`() =
        runTest {
            val provider = FallbackAiLibrarianProvider(preferred = alwaysAnswers(), fallback = heuristic)

            val answer = provider.answer(request())

            assertEquals(AiLibrarianAnswerSource.ON_DEVICE_LLM, answer.source)
            assertNull(answer.degradedFrom)
            assertEquals(AiLibrarianProviderId.ON_DEVICE_LLM, provider.id)
        }

    @Test
    fun `provider failure degrades to the verified heuristic answer`() =
        runTest {
            val provider =
                FallbackAiLibrarianProvider(
                    preferred = alwaysFails(AiLibrarianProviderErrorKind.UNAVAILABLE),
                    fallback = heuristic,
                )

            val answer = provider.answer(request())

            assertEquals(AiLibrarianAnswerSource.HEURISTIC, answer.source)
            assertEquals(AiLibrarianProviderErrorKind.UNAVAILABLE, answer.degradedFrom)
            // 縮退した回答も必ず参照refを持ち、未検証の部分回答にならない。
            assertFalse(answer.entries.isEmpty())
            assertFalse(answer.referencedRefs.isEmpty())
            assertNull(answer.summary)
        }

    @Test
    fun `invalid llm output degrades instead of surfacing a partial answer`() =
        runTest {
            val provider =
                FallbackAiLibrarianProvider(
                    preferred = alwaysFails(AiLibrarianProviderErrorKind.INVALID_RESPONSE),
                    fallback = heuristic,
                )

            val answer = provider.answer(request())

            assertEquals(AiLibrarianProviderErrorKind.INVALID_RESPONSE, answer.degradedFrom)
            answer.entries.forEach { entry -> assertNull(entry.comment) }
        }

    @Test
    fun `cancellation is not degraded`() =
        runTest {
            val provider =
                FallbackAiLibrarianProvider(
                    preferred =
                        object : AiLibrarianProvider {
                            override val id = AiLibrarianProviderId.ON_DEVICE_LLM
                            override val sendsDataOffDevice = false

                            override suspend fun answer(request: AiLibrarianRequest): AiLibrarianAnswer =
                                throw CancellationException("cancelled by user")
                        },
                    fallback = heuristic,
                )

            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking { provider.answer(request()) }
            }
        }

    @Test
    fun `disabled preferred provider is never invoked`() =
        runTest {
            var invoked = false
            val provider =
                FallbackAiLibrarianProvider(
                    preferred =
                        object : AiLibrarianProvider {
                            override val id = AiLibrarianProviderId.ON_DEVICE_LLM
                            override val sendsDataOffDevice = false

                            override suspend fun answer(request: AiLibrarianRequest): AiLibrarianAnswer {
                                invoked = true
                                throw AiLibrarianProviderException(AiLibrarianProviderErrorKind.UNAVAILABLE)
                            }
                        },
                    fallback = heuristic,
                    preferredEnabled = { false },
                )

            val answer = provider.answer(request())

            assertFalse(invoked)
            assertEquals(AiLibrarianProviderId.ON_DEVICE_HEURISTIC, provider.id)
            assertEquals(AiLibrarianAnswerSource.HEURISTIC, answer.source)
            assertNull(answer.degradedFrom)
        }

    @Test
    fun `degrading notifies the diagnostics hook exactly once`() =
        runTest {
            val degraded = mutableListOf<AiLibrarianProviderErrorKind>()
            val provider =
                FallbackAiLibrarianProvider(
                    preferred = alwaysFails(AiLibrarianProviderErrorKind.INVALID_RESPONSE),
                    fallback = heuristic,
                    onDegraded = { kind -> degraded += kind },
                )

            provider.answer(request())

            assertEquals(listOf(AiLibrarianProviderErrorKind.INVALID_RESPONSE), degraded)
        }

    @Test
    fun `composite never sends data off device`() {
        val provider = FallbackAiLibrarianProvider(preferred = alwaysAnswers(), fallback = heuristic)

        assertFalse(provider.sendsDataOffDevice)
    }

    private fun alwaysAnswers(): AiLibrarianProvider =
        object : AiLibrarianProvider {
            override val id = AiLibrarianProviderId.ON_DEVICE_LLM
            override val sendsDataOffDevice = false

            override suspend fun answer(request: AiLibrarianRequest): AiLibrarianAnswer =
                AiLibrarianAnswer(
                    intent = dev.ndcshelf.app.domain.ai.AiLibrarianIntent.OVERVIEW,
                    entries =
                        listOf(
                            dev.ndcshelf.app.domain.ai.AiLibrarianAnswerEntry(
                                label = null,
                                reason = dev.ndcshelf.app.domain.ai.AiLibrarianReason.LIBRARY_OVERVIEW,
                                refs = listOf("1"),
                            ),
                        ),
                    summary = "端末内LLMの提案です。",
                    source = AiLibrarianAnswerSource.ON_DEVICE_LLM,
                )
        }

    private fun alwaysFails(kind: AiLibrarianProviderErrorKind): AiLibrarianProvider =
        object : AiLibrarianProvider {
            override val id = AiLibrarianProviderId.ON_DEVICE_LLM
            override val sendsDataOffDevice = false

            override suspend fun answer(request: AiLibrarianRequest): AiLibrarianAnswer = throw AiLibrarianProviderException(kind)
        }

    private fun request() =
        AiLibrarianRequest(
            question = "次に読む本を選んでください",
            includedFields = listOf(AiLibrarianField.TITLE),
            items =
                listOf(
                    AiLibrarianItem(ref = "1", title = "匿名サンプル図書A"),
                    AiLibrarianItem(ref = "2", title = "匿名サンプル図書B"),
                ),
        )
}
