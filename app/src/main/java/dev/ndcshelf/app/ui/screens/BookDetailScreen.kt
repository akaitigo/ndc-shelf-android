package dev.ndcshelf.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.ui.components.BookCover
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BookDetailScreen(
    copies: List<LibraryBook>,
    onBack: () -> Unit,
    onEditCopy: (String) -> Unit,
    onEditBibliography: () -> Unit,
    onReconcile: () -> Unit,
    onManageSeries: (String) -> Unit = {},
    contentPadding: PaddingValues,
) {
    require(copies.isNotEmpty())
    require(copies.map(LibraryBook::editionId).distinct().size == 1)
    val edition = copies.first()
    val physicalLabel = stringResource(R.string.book_detail_media_physical)
    val digitalLabel = stringResource(R.string.book_detail_media_digital)
    val mediaLabels = listOfNotNull(
        physicalLabel.takeIf { copies.any { it.mediaType == MediaType.PHYSICAL } },
        digitalLabel.takeIf { copies.any { it.mediaType == MediaType.DIGITAL } },
    ).joinToString("・")
    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = Modifier.testTag(BOOK_DETAIL_TEST_TAG),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.book_detail_back),
                    )
                }
                Text(
                    stringResource(R.string.book_detail_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                BookCover(
                    coverUrl = edition.coverUrl,
                    title = edition.title,
                    width = 112.dp,
                    height = 160.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        edition.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        edition.primaryAuthor,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SourceBadge(edition.bibliographicSource)
                }
            }
        }

        item {
            DetailSection(stringResource(R.string.book_detail_edition_section)) {
                DetailValue(
                    stringResource(R.string.book_detail_isbn),
                    edition.isbn13 ?: stringResource(R.string.book_detail_no_isbn),
                )
                DetailValue(
                    stringResource(R.string.book_detail_publisher),
                    edition.publisher ?: stringResource(R.string.book_detail_unknown),
                )
                DetailValue(
                    stringResource(R.string.book_detail_year),
                    edition.publishedYear?.toString() ?: stringResource(R.string.book_detail_unknown),
                )
                DetailValue(
                    stringResource(R.string.book_detail_media),
                    mediaLabels,
                )
            }
        }

        item {
            DetailSection(stringResource(R.string.book_detail_classification_section)) {
                DetailValue(
                    stringResource(R.string.book_detail_ndc),
                    edition.ndcCode?.let { code ->
                        "$code${edition.ndcCategory?.label?.let { " · $it" }.orEmpty()}"
                    } ?: stringResource(R.string.book_detail_unclassified),
                )
                DetailValue(
                    stringResource(R.string.book_detail_ndc_edition),
                    edition.ndcEdition ?: stringResource(R.string.book_detail_unknown),
                )
                DetailValue(
                    stringResource(R.string.book_detail_classification_source),
                    edition.classificationSource.label(),
                )
            }
        }

        item {
            SectionHeading(
                stringResource(R.string.book_detail_copies_section, copies.size),
            )
        }
        items(
            items = copies.sortedWith(compareBy(LibraryBook::addedAt).thenBy(LibraryBook::copyId)),
            key = LibraryBook::copyId,
        ) { copy ->
            CopyDetailCard(copy = copy, onClick = { onEditCopy(copy.copyId) })
        }

        item {
            DetailSection(stringResource(R.string.book_detail_actions_section)) {
                Button(onClick = onEditBibliography, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.book_detail_edit_bibliography))
                }
                if (edition.bibliographicSource == BibliographicSource.MANUAL) {
                    OutlinedButton(onClick = onReconcile, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Sync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.book_detail_reconcile))
                    }
                }
                Text(
                    stringResource(R.string.book_detail_copy_action_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            DetailSection(stringResource(R.string.book_detail_related_section)) {
                Text(
                    stringResource(R.string.book_detail_series_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onManageSeries(edition.workId) }) {
                    Text(stringResource(R.string.book_detail_series_action))
                }
            }
        }
    }
}

@Composable
private fun CopyDetailCard(copy: LibraryBook, onClick: () -> Unit) {
    val description = stringResource(
        R.string.book_detail_copy_description,
        copy.copyLabel,
        copy.location,
        copy.readingStatus.label,
        copy.mediaType.label(),
    )
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(copy.copyLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    copy.location,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${copy.readingStatus.label} · ${copy.mediaType.label()} · " +
                        DateFormat.getDateInstance().format(Date(copy.addedAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeading(title)
            content()
        }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun DetailValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SourceBadge(source: BibliographicSource) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (source == BibliographicSource.MANUAL) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
    ) {
        Text(
            stringResource(
                if (source == BibliographicSource.MANUAL) {
                    R.string.book_detail_source_manual
                } else {
                    R.string.book_detail_source_ndl
                },
            ),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun MediaType.label(): String = when (this) {
    MediaType.PHYSICAL -> stringResource(R.string.book_detail_media_physical)
    MediaType.DIGITAL -> stringResource(R.string.book_detail_media_digital)
}

@Composable
private fun ClassificationSource.label(): String = when (this) {
    ClassificationSource.NDL -> stringResource(R.string.book_detail_classification_ndl)
    ClassificationSource.MANUAL -> stringResource(R.string.book_detail_classification_manual)
    ClassificationSource.UNKNOWN -> stringResource(R.string.book_detail_unknown)
}

internal const val BOOK_DETAIL_TEST_TAG = "book-detail"

@Preview(name = "表紙なし・未知分類", showBackground = true)
@Composable
private fun BookDetailMissingPreview() {
    NdcShelfTheme {
        BookDetailScreen(
            copies = listOf(previewBook()),
            onBack = {},
            onEditCopy = {},
            onEditBibliography = {},
            onReconcile = {},
            contentPadding = PaddingValues(),
        )
    }
}

@Preview(name = "長文・複数冊", showBackground = true)
@Composable
private fun BookDetailLongMultiplePreview() {
    val first = previewBook().copy(
        title = "とても長いタイトルを持つ資料の完全版・改訂版・保存版",
        primaryAuthor = "複数の著者名と編者名が連続する長い著者表示",
    )
    NdcShelfTheme {
        BookDetailScreen(
            copies = listOf(
                first.copy(copyId = "copy-1", copyLabel = "閲覧用"),
                first.copy(copyId = "copy-2", copyLabel = "保存用", location = "書庫 / 本棚B / 上段"),
            ),
            onBack = {},
            onEditCopy = {},
            onEditBibliography = {},
            onReconcile = {},
            contentPadding = PaddingValues(),
        )
    }
}

private fun previewBook() = LibraryBook(
    copyId = "copy",
    workId = "work",
    editionId = "edition",
    title = "郷土資料",
    primaryAuthor = "著者不明",
    isbn13 = null,
    publisher = null,
    publishedYear = null,
    coverUrl = null,
    ndcCode = null,
    ndcEdition = null,
    classificationSource = ClassificationSource.UNKNOWN,
    mediaType = MediaType.PHYSICAL,
    location = "未設定",
    readingStatus = ReadingStatus.UNREAD,
    addedAt = 1_700_000_000_000,
    bibliographicSource = BibliographicSource.MANUAL,
)
