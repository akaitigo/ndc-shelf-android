package dev.ndcshelf.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ndcshelf.app.R
import kotlinx.serialization.Serializable

/**
 * 画面遷移のroute定義。routeへは安定IDだけを渡し、DBモデルや個人データを
 * Bundleへ保存しない（docs/NAVIGATION.md参照）。
 */
@Serializable
data object LibraryGraph

@Serializable
data object SeriesGraph

@Serializable
data object DataGraph

@Serializable
data object LibraryRoute

@Serializable
data object ScanRoute

@Serializable
data object SeriesRoute

@Serializable
data object InsightsRoute

@Serializable
data object DataRoute

@Serializable
data object InfoRoute

@Serializable
data class WorkVariantRoute(
    val workId: String,
)

@Serializable
data class SeriesSuggestionRoute(
    val workId: String? = null,
)

@Serializable
data object ConsentRoute

@Serializable
data object TagManagementRoute

@Serializable
data object OnboardingRoute

enum class TopLevelDestination(
    val route: Any,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    LIBRARY(LibraryGraph, R.string.navigation_library, Icons.AutoMirrored.Rounded.LibraryBooks),
    SCAN(ScanRoute, R.string.navigation_scan, Icons.Rounded.QrCodeScanner),
    SERIES(SeriesGraph, R.string.navigation_series, Icons.Rounded.CollectionsBookmark),
    INSIGHTS(InsightsRoute, R.string.navigation_insights, Icons.Rounded.Analytics),
    DATA(DataGraph, R.string.navigation_data, Icons.Rounded.Storage),
    INFO(InfoRoute, R.string.navigation_info, Icons.Rounded.Info),
}
