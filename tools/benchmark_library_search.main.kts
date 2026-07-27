import kotlin.system.measureNanoTime

data class SampleBook(
    val title: String,
    val author: String,
    val isbn: String,
    val ndc: String,
    val location: String,
    val status: String,
    val addedAt: Long,
)

fun dataset(size: Int): List<SampleBook> = List(size) { index ->
    SampleBook(
        title = if (index % 97 == 0) "郷土資料 $index" else "資料 $index",
        author = "著者 ${index % 431}",
        isbn = "978${index.toString().padStart(10, '0')}",
        ndc = "%03d".format(index % 1_000),
        location = "部屋${index % 8} / 棚${index % 32} / 段${index % 5}",
        status = listOf("UNREAD", "READING", "READ", "PAUSED")[index % 4],
        addedAt = 1_700_000_000_000L + index,
    )
}

fun search(books: List<SampleBook>, query: String): List<SampleBook> = books
    .filter { book ->
        listOf(book.title, book.author, book.isbn, book.ndc, book.location, book.status)
            .any { it.contains(query, ignoreCase = true) }
    }
    .sortedBy { it.title }

println("size,median_ms,result_count,retained_kib")
listOf(1_000, 5_000, 20_000).forEach { size ->
    val books = dataset(size)
    repeat(5) { search(books, "郷土") }
    val times = List(25) {
        measureNanoTime { search(books, "郷土") }
    }.sorted()
    val retainedBytes = books.sumOf { book ->
        listOf(book.title, book.author, book.isbn, book.ndc, book.location, book.status)
            .sumOf { it.length * 2L }
    }
    println("$size,${times[times.size / 2] / 1_000_000.0},${search(books, "郷土").size},${retainedBytes / 1024}")
}
