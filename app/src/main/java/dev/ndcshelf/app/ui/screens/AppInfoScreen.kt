package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import com.mikepenz.aboutlibraries.util.withContext
import dev.ndcshelf.app.R

private const val NDL_API_URL = "https://ndlsearch.ndl.go.jp/help/api"
private const val NDL_PROVIDER_URL = "https://ndlsearch.ndl.go.jp/help/api/provider"
private const val ML_KIT_DATA_URL = "https://developers.google.com/ml-kit/android-data-disclosure"
private const val REPOSITORY_URL = "https://github.com/akaitigo/ndc-shelf-android"
private const val ISSUE_URL = "$REPOSITORY_URL/issues/new/choose"
private const val SECURITY_URL = "$REPOSITORY_URL/security/advisories/new"

@Composable
fun AppInfoScreen(
    versionName: String,
    versionCode: Int,
    buildType: String,
    onOpenUrl: (String) -> Unit,
    contentPadding: PaddingValues,
    onReplayOnboarding: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val libraries =
        remember(context) {
            runCatching { Libs.Builder().withContext(context).build() }.getOrNull()
        }
    var page by rememberSaveable { mutableStateOf(InfoPage.OVERVIEW) }
    var selectedLibraryId by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedLibrary =
        libraries?.libraries?.firstOrNull {
            it.uniqueId == selectedLibraryId
        }
    when {
        selectedLibrary != null -> {
            LibraryLicenseDetail(
                library = selectedLibrary,
                onBack = { selectedLibraryId = null },
                onOpenUrl = onOpenUrl,
                contentPadding = contentPadding,
            )
        }

        page == InfoPage.LIBRARIES -> {
            LibraryLicenseList(
                libraries = libraries,
                onBack = { page = InfoPage.OVERVIEW },
                onSelect = { selectedLibraryId = it.uniqueId },
                contentPadding = contentPadding,
            )
        }

        page == InfoPage.APP_LICENSE -> {
            AppLicenseDetail(
                license = libraries?.licenses?.firstOrNull { it.spdxId == "Apache-2.0" },
                onBack = { page = InfoPage.OVERVIEW },
                contentPadding = contentPadding,
            )
        }

        else -> {
            InfoOverview(
                versionName = versionName,
                versionCode = versionCode,
                buildType = buildType,
                libraryCount = libraries?.libraries?.size,
                onShowAppLicense = { page = InfoPage.APP_LICENSE },
                onShowLibraries = { page = InfoPage.LIBRARIES },
                onOpenUrl = onOpenUrl,
                onReplayOnboarding = onReplayOnboarding,
                contentPadding = contentPadding,
            )
        }
    }
}

@Composable
private fun InfoOverview(
    versionName: String,
    versionCode: Int,
    buildType: String,
    libraryCount: Int?,
    onShowAppLicense: () -> Unit,
    onShowLibraries: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onReplayOnboarding: (() -> Unit)?,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(INFO_OVERVIEW_TAG),
        contentPadding = screenPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeading(
                title = stringResource(R.string.info_title),
                description = stringResource(R.string.info_description),
            )
        }
        item {
            InfoCard(title = stringResource(R.string.info_version_title)) {
                Text(
                    stringResource(R.string.info_version_value, versionName, versionCode, buildType),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (onReplayOnboarding != null) {
            item {
                OutlinedButton(
                    onClick = onReplayOnboarding,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.info_replay_onboarding))
                }
            }
        }
        item { SectionTitle(stringResource(R.string.info_privacy_title)) }
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    text = stringResource(R.string.info_privacy_summary),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            InfoCard(title = stringResource(R.string.info_privacy_device_title)) {
                Text(stringResource(R.string.info_privacy_device_body))
            }
        }
        item {
            InfoCard(title = stringResource(R.string.info_privacy_network_title)) {
                Text(stringResource(R.string.info_privacy_network_body))
                ExternalButton(
                    label = stringResource(R.string.info_mlkit_data_link),
                    onClick = { onOpenUrl(ML_KIT_DATA_URL) },
                )
            }
        }
        item {
            InfoCard(title = stringResource(R.string.info_privacy_backup_title)) {
                Text(stringResource(R.string.info_privacy_backup_body))
            }
        }
        item { SectionTitle(stringResource(R.string.info_source_title)) }
        item {
            InfoCard(title = stringResource(R.string.info_source_ndl_title)) {
                Text(stringResource(R.string.info_source_ndl_body))
                ExternalButton(
                    label = stringResource(R.string.info_source_api_link),
                    onClick = { onOpenUrl(NDL_API_URL) },
                )
                ExternalButton(
                    label = stringResource(R.string.info_source_provider_link),
                    onClick = { onOpenUrl(NDL_PROVIDER_URL) },
                )
            }
        }
        item { SectionTitle(stringResource(R.string.info_licenses_title)) }
        item {
            NavigationCard(
                title = stringResource(R.string.info_app_license_title),
                description = stringResource(R.string.info_app_license_summary),
                testTag = APP_LICENSE_BUTTON_TAG,
                onClick = onShowAppLicense,
            )
        }
        item {
            NavigationCard(
                title = stringResource(R.string.info_oss_title),
                description =
                    if (libraryCount == null) {
                        stringResource(R.string.info_oss_unavailable)
                    } else {
                        pluralStringResource(R.plurals.info_oss_summary, libraryCount, libraryCount)
                    },
                testTag = OSS_LICENSE_BUTTON_TAG,
                enabled = libraryCount != null,
                onClick = onShowLibraries,
            )
        }
        item { SectionTitle(stringResource(R.string.info_contact_title)) }
        item {
            InfoCard(title = stringResource(R.string.info_contact_general_title)) {
                Text(stringResource(R.string.info_contact_general_body))
                ExternalButton(
                    label = stringResource(R.string.info_repository_link),
                    onClick = { onOpenUrl(REPOSITORY_URL) },
                )
                ExternalButton(
                    label = stringResource(R.string.info_issue_link),
                    onClick = { onOpenUrl(ISSUE_URL) },
                )
            }
        }
        item {
            InfoCard(
                title = stringResource(R.string.info_security_title),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) {
                Text(stringResource(R.string.info_security_body))
                ExternalButton(
                    label = stringResource(R.string.info_security_link),
                    onClick = { onOpenUrl(SECURITY_URL) },
                )
            }
        }
    }
}

@Composable
private fun LibraryLicenseList(
    libraries: Libs?,
    onBack: () -> Unit,
    onSelect: (Library) -> Unit,
    contentPadding: PaddingValues,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered =
        remember(libraries, query) {
            libraries?.libraries.orEmpty().filter { library ->
                query.isBlank() ||
                    listOf(
                        library.name,
                        library.uniqueId,
                        library.artifactVersion.orEmpty(),
                        libraryAttribution(library),
                    ).any { it.contains(query, ignoreCase = true) }
            }
        }
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(OSS_LICENSE_LIST_TAG),
        contentPadding = screenPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            DetailHeading(stringResource(R.string.info_oss_title), onBack)
            Text(
                text = pluralStringResource(
                        R.plurals.info_oss_offline_notice,
                        libraries?.libraries?.size ?: 0,
                        libraries?.libraries?.size ?: 0,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                label = { Text(stringResource(R.string.info_oss_search)) },
                singleLine = true,
            )
        }
        items(filtered, key = { it.uniqueId }) { library ->
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(library) },
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(library.name, fontWeight = FontWeight.Bold)
                        Text(
                            text = library.artifactVersion.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text =
                                stringResource(
                                    R.string.info_oss_license_names,
                                    library.licenses.joinToString { it.name },
                                ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text =
                                stringResource(
                                    R.string.info_oss_attribution,
                                    libraryAttribution(library),
                                ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun LibraryLicenseDetail(
    library: Library,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DetailHeading(library.name, onBack) }
        item {
            InfoCard(title = stringResource(R.string.info_oss_component_title)) {
                Text(stringResource(R.string.info_oss_coordinate, library.uniqueId))
                library.artifactVersion?.let {
                    Text(stringResource(R.string.info_oss_version, it))
                }
                Text(stringResource(R.string.info_oss_attribution, libraryAttribution(library)))
                library.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                library.website?.let { website ->
                    ExternalButton(
                        label = stringResource(R.string.info_oss_website),
                        onClick = { onOpenUrl(website) },
                    )
                }
            }
        }
        items(library.licenses.toList(), key = { it.hash }) { license ->
            LicenseCard(license = license, onOpenUrl = onOpenUrl)
        }
    }
}

@Composable
private fun AppLicenseDetail(
    license: License?,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(APP_LICENSE_DETAIL_TAG),
        contentPadding = screenPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DetailHeading(stringResource(R.string.info_app_license_title), onBack) }
        item {
            Text(
                text = stringResource(R.string.info_app_copyright),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Text(
                text =
                    license?.licenseContent
                        ?: stringResource(R.string.info_license_content_unavailable),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LicenseCard(
    license: License,
    onOpenUrl: (String) -> Unit,
) {
    InfoCard(title = license.name) {
        val content = license.licenseContent
        if (content.isNullOrBlank()) {
            Text(stringResource(R.string.info_external_terms_notice))
        } else {
            Text(content, style = MaterialTheme.typography.bodySmall)
        }
        license.url?.let { url ->
            ExternalButton(
                label = stringResource(R.string.info_license_latest_link),
                onClick = { onOpenUrl(url) },
            )
        }
    }
}

@Composable
private fun ScreenHeading(
    title: String,
    description: String,
) {
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = description,
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DetailHeading(
    title: String,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.info_back),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier =
            Modifier
                .padding(top = 10.dp)
                .semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun InfoCard(
    title: String,
    colors: androidx.compose.material3.CardColors = CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = colors,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun NavigationCard(
    title: String,
    description: String,
    testTag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(testTag)
                .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun ExternalButton(
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
    }
}

internal fun libraryAttribution(library: Library): String =
    buildList {
        addAll(library.developers.mapNotNull { it.name?.takeIf(String::isNotBlank) })
        library.organization
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
    }.distinct().ifEmpty { listOf(library.uniqueId) }.joinToString()

private fun screenPadding(contentPadding: PaddingValues) =
    PaddingValues(
        start = 16.dp,
        top = contentPadding.calculateTopPadding() + 20.dp,
        end = 16.dp,
        bottom = contentPadding.calculateBottomPadding() + 24.dp,
    )

private enum class InfoPage { OVERVIEW, APP_LICENSE, LIBRARIES }

const val INFO_OVERVIEW_TAG = "info_overview"
const val APP_LICENSE_BUTTON_TAG = "app_license_button"
const val APP_LICENSE_DETAIL_TAG = "app_license_detail"
const val OSS_LICENSE_BUTTON_TAG = "oss_license_button"
const val OSS_LICENSE_LIST_TAG = "oss_license_list"
