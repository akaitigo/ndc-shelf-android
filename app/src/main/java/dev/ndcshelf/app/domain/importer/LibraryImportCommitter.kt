package dev.ndcshelf.app.domain.importer

import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.text.UiMessage
import kotlinx.coroutines.CancellationException

internal class LibraryImportCommitter(
    private val readCurrentBooks: suspend () -> List<LibraryBook>,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit,
    private val writeBooks: suspend (List<LibraryBook>, LibraryImportPreview) -> Unit,
) {
    suspend fun commit(preview: LibraryImportPreview): ImportApplyResult =
        try {
            runInTransaction {
                if (readCurrentBooks() != preview.existingSnapshot) {
                    throw StalePreviewException()
                }
                if (preview.changeCount > 0 || preview.tagDefinitions.isNotEmpty()) {
                    writeBooks(preview.additions + preview.updates, preview)
                }
            }
            ImportApplyResult.Applied(
                addedCount = preview.additions.size,
                updatedCount = preview.updates.size,
                skippedCount = preview.skippedCount,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: StalePreviewException) {
            ImportApplyResult.StalePreview
        } catch (_: Exception) {
            ImportApplyResult.Failure(UiMessage(R.string.import_apply_failed))
        }

    private class StalePreviewException : IllegalStateException()
}
