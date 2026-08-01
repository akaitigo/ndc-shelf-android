package dev.ndcshelf.app.domain.ai.llm

import dev.ndcshelf.app.domain.ai.AiLibrarianAnswer
import dev.ndcshelf.app.domain.ai.AiLibrarianProvider
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderErrorKind
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderException
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderId
import dev.ndcshelf.app.domain.ai.AiLibrarianRequest
import kotlinx.coroutines.CancellationException

/**
 * 端末内LLMを第一候補にし、失敗時は規則ベースの[primary]縮退先へ切り替える合成プロバイダ。
 *
 * 縮退先の回答は決定的で完全に検証済みのため、「未検証の部分回答」を表示することはない。
 * 縮退したことは[AiLibrarianAnswer.degradedFrom]でUIへ伝える。
 *
 * キャンセルは縮退させずそのまま伝播する（利用者の中止操作を勝手に別経路で続行しない）。
 */
class FallbackAiLibrarianProvider(
    private val preferred: AiLibrarianProvider,
    private val fallback: AiLibrarianProvider,
    private val preferredEnabled: () -> Boolean = { true },
    private val onDegraded: (AiLibrarianProviderErrorKind) -> Unit = {},
) : AiLibrarianProvider {
    override val id: AiLibrarianProviderId
        get() = if (preferredEnabled()) preferred.id else fallback.id

    override val sendsDataOffDevice: Boolean =
        preferred.sendsDataOffDevice || fallback.sendsDataOffDevice

    override suspend fun answer(request: AiLibrarianRequest): AiLibrarianAnswer {
        if (!preferredEnabled()) return fallback.answer(request)
        return try {
            preferred.answer(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: AiLibrarianProviderException) {
            fallbackAnswer(request, failure.kind)
        }
    }

    private suspend fun fallbackAnswer(
        request: AiLibrarianRequest,
        degradedFrom: AiLibrarianProviderErrorKind,
    ): AiLibrarianAnswer {
        runCatching { onDegraded(degradedFrom) }
        return fallback.answer(request).copy(degradedFrom = degradedFrom)
    }
}
