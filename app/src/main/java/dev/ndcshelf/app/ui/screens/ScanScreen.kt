package dev.ndcshelf.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ndcshelf.app.R
import dev.ndcshelf.app.BookstoreUiState
import dev.ndcshelf.app.ScanFailure
import dev.ndcshelf.app.ScanSessionUiState
import dev.ndcshelf.app.ScanUiState
import dev.ndcshelf.app.domain.model.BookstoreBook
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.PurchaseTransition
import dev.ndcshelf.app.domain.model.ScanAttemptOutcome
import dev.ndcshelf.app.domain.model.ScanSession
import dev.ndcshelf.app.ui.components.CameraPreview
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun ScanScreen(
    scanState: ScanUiState,
    bookstoreState: BookstoreUiState,
    wishlist: List<BookstoreBook>,
    scanSessions: List<ScanSession>,
    scanSessionState: ScanSessionUiState,
    onSubmitIsbn: (String) -> Unit,
    onLookupBookstore: (String) -> Unit,
    onCameraError: (String) -> Unit,
    onBookstoreCameraError: (String) -> Unit,
    onRetry: () -> Unit,
    onRetryBookstore: () -> Unit,
    onClearState: () -> Unit,
    onClearBookstoreState: () -> Unit,
    onAddDuplicateCopy: (String) -> Unit = {},
    onChangePurchaseState: (PurchaseTransition) -> Unit,
    onSelectWishlistItem: (BookstoreBook) -> Unit,
    onStartScanSession: () -> Unit,
    onFinishScanSession: (String) -> Unit,
    onUndoScanAttempt: (String) -> Unit,
    onUndoScanSession: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val permissionPreferences = remember(context) {
        context.getSharedPreferences(CAMERA_PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by rememberSaveable {
        mutableStateOf(permissionPreferences.getBoolean(CAMERA_PERMISSION_REQUESTED, false))
    }
    var permissionPermanentlyDenied by rememberSaveable {
        mutableStateOf(
            !hasCameraPermission && permissionRequested &&
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false,
        )
    }
    var successSequence by remember { mutableIntStateOf(0) }
    var showSuccessFeedback by remember { mutableStateOf(false) }
    var cameraRestartSequence by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        permissionPermanentlyDenied = !granted && permissionRequested &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
    }
    var mode by rememberSaveable { mutableStateOf(ScanMode.LIBRARY) }
    var wishlistQuery by rememberSaveable { mutableStateOf("") }
    val visibleWishlist = remember(wishlist, wishlistQuery) {
        wishlist.filter { it.matches(wishlistQuery) }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !permissionRequested) {
            permissionRequested = true
            permissionPreferences.edit { putBoolean(CAMERA_PERMISSION_REQUESTED, true) }
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(successSequence) {
        if (successSequence == 0) return@LaunchedEffect
        showSuccessFeedback = true
        delay(700)
        showSuccessFeedback = false
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                permissionPermanentlyDenied = !hasCameraPermission && permissionRequested &&
                    activity?.shouldShowRequestPermissionRationale(
                        Manifest.permission.CAMERA,
                    ) == false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding(),
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = stringResource(
                    if (mode == ScanMode.LIBRARY) R.string.scan_title else R.string.bookstore_title,
                ),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    if (mode == ScanMode.LIBRARY) {
                        R.string.scan_description
                    } else {
                        R.string.bookstore_description
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == ScanMode.LIBRARY,
                    onClick = { mode = ScanMode.LIBRARY },
                    label = { Text(stringResource(R.string.scan_mode_library)) },
                )
                FilterChip(
                    selected = mode == ScanMode.BOOKSTORE,
                    onClick = { mode = ScanMode.BOOKSTORE },
                    label = { Text(stringResource(R.string.scan_mode_bookstore)) },
                )
            }
        }

        if (mode == ScanMode.LIBRARY) {
            item {
                ScanSessionPanel(
                    sessions = scanSessions,
                    state = scanSessionState,
                    onStart = onStartScanSession,
                    onFinish = onFinishScanSession,
                    onUndoAttempt = onUndoScanAttempt,
                    onUndoSession = onUndoScanSession,
                )
            }
        }

        item {
            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(310.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.Black),
                ) {
                    key(cameraRestartSequence) {
                        CameraPreview(
                            onIsbnDetected = { isbn ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                successSequence += 1
                                if (mode == ScanMode.LIBRARY) {
                                    onSubmitIsbn(isbn)
                                } else {
                                    onLookupBookstore(isbn)
                                }
                            },
                            onCameraError = if (mode == ScanMode.LIBRARY) {
                                onCameraError
                            } else {
                                onBookstoreCameraError
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(width = 270.dp, height = 116.dp)
                            .border(
                                width = 3.dp,
                                color = if (showSuccessFeedback) {
                                    Color(0xFF62D49C)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                shape = RoundedCornerShape(18.dp),
                            ),
                    )
                    if (showSuccessFeedback) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).padding(14.dp),
                            color = Color(0xE6205D45),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = Color.White,
                                )
                                Text(
                                    stringResource(R.string.camera_scan_success),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(14.dp),
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.camera_scan_guide),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            } else {
                CameraPermissionCard(
                    permanentlyDenied = permissionPermanentlyDenied,
                    onRequestPermission = {
                        permissionRequested = true
                        permissionPreferences.edit {
                            putBoolean(CAMERA_PERMISSION_REQUESTED, true)
                        }
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                )
            }
        }

        item {
            if (mode == ScanMode.LIBRARY) {
                ScanResultCard(
                    state = scanState,
                    onRetry = onRetry,
                    onCameraRetry = {
                        onClearState()
                        cameraRestartSequence += 1
                    },
                    onClear = onClearState,
                    onAddDuplicateCopy = onAddDuplicateCopy,
                )
            } else {
                BookstoreResultCard(
                    state = bookstoreState,
                    onRetry = onRetryBookstore,
                    onCameraRetry = {
                        onClearBookstoreState()
                        cameraRestartSequence += 1
                    },
                    onClear = onClearBookstoreState,
                    onChangeState = onChangePurchaseState,
                )
            }
        }

        item {
            ManualIsbnEntry(
                isLoading = if (mode == ScanMode.LIBRARY) {
                    scanState is ScanUiState.Loading
                } else {
                    bookstoreState is BookstoreUiState.Loading ||
                        bookstoreState is BookstoreUiState.Updating
                },
                onSubmit = if (mode == ScanMode.LIBRARY) onSubmitIsbn else onLookupBookstore,
            )
        }

        if (mode == ScanMode.BOOKSTORE) {
            item {
                Text(
                    text = stringResource(R.string.bookstore_saved_title, wishlist.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedTextField(
                    value = wishlistQuery,
                    onValueChange = { wishlistQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.bookstore_search)) },
                    singleLine = true,
                )
            }
            if (visibleWishlist.isEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            if (wishlist.isEmpty()) {
                                R.string.bookstore_empty
                            } else {
                                R.string.bookstore_no_match
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(visibleWishlist, key = BookstoreBook::editionId) { book ->
                    WishlistCard(book = book, onClick = { onSelectWishlistItem(book) })
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "書誌情報とNDC分類の一部は、国立国会図書館サーチAPIを利用しています。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class ScanMode { LIBRARY, BOOKSTORE }

@Composable
internal fun ScanSessionPanel(
    sessions: List<ScanSession>,
    state: ScanSessionUiState,
    onStart: () -> Unit,
    onFinish: (String) -> Unit,
    onUndoAttempt: (String) -> Unit,
    onUndoSession: (String) -> Unit,
) {
    val active = sessions.firstOrNull(ScanSession::isActive)
    var pendingAttemptId by remember { mutableStateOf<String?>(null) }
    var pendingSessionId by remember { mutableStateOf<String?>(null) }
    val busy = state is ScanSessionUiState.Working

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.scan_session_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (active == null) {
                Text(
                    stringResource(R.string.scan_session_inactive),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onStart, enabled = !busy) {
                    Text(stringResource(R.string.scan_session_start))
                }
            } else {
                Text(
                    stringResource(
                        R.string.scan_session_active_count,
                        active.attempts.size,
                        active.activeAddedCount,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { onFinish(active.id) },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.scan_session_finish)) }
                    TextButton(
                        onClick = { pendingSessionId = active.id },
                        enabled = !busy && active.activeAddedCount > 0,
                    ) { Text(stringResource(R.string.scan_session_undo_all)) }
                }
            }
            when (state) {
                is ScanSessionUiState.Undone -> Text(
                    stringResource(R.string.scan_session_undone, state.count),
                    color = MaterialTheme.colorScheme.primary,
                )
                ScanSessionUiState.Conflict -> Text(
                    stringResource(R.string.scan_session_conflict),
                    color = MaterialTheme.colorScheme.error,
                )
                ScanSessionUiState.NotFound -> Text(
                    stringResource(R.string.scan_session_not_found),
                    color = MaterialTheme.colorScheme.error,
                )
                ScanSessionUiState.Error -> Text(
                    stringResource(R.string.scan_session_error),
                    color = MaterialTheme.colorScheme.error,
                )
                ScanSessionUiState.Idle, ScanSessionUiState.Working -> Unit
            }
            if (sessions.isNotEmpty()) {
                Text(
                    stringResource(R.string.scan_session_history),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                sessions.take(3).forEach { session ->
                    val started = remember(session.startedAt) {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(session.startedAt))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.scan_session_started_at, started),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                stringResource(
                                    R.string.scan_session_history_summary,
                                    session.attempts.size,
                                    session.activeAddedCount,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        if (session.activeAddedCount > 0) {
                            TextButton(
                                onClick = { pendingSessionId = session.id },
                                enabled = !busy,
                            ) { Text(stringResource(R.string.scan_session_undo_all_short)) }
                        }
                    }
                    session.attempts.take(5).forEach { attempt ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(attempt.isbn, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    attempt.outcome.label(attempt.undoneAt != null),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (attempt.outcome == ScanAttemptOutcome.ADDED &&
                                attempt.undoneAt == null
                            ) {
                                TextButton(
                                    onClick = { pendingAttemptId = attempt.id },
                                    enabled = !busy,
                                ) { Text(stringResource(R.string.scan_session_undo_one)) }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingAttemptId?.let { attemptId ->
        ScanUndoConfirmation(
            message = stringResource(R.string.scan_session_undo_one_confirm),
            onConfirm = {
                pendingAttemptId = null
                onUndoAttempt(attemptId)
            },
            onDismiss = { pendingAttemptId = null },
        )
    }
    pendingSessionId?.let { sessionId ->
        ScanUndoConfirmation(
            message = stringResource(R.string.scan_session_undo_all_confirm),
            onConfirm = {
                pendingSessionId = null
                onUndoSession(sessionId)
            },
            onDismiss = { pendingSessionId = null },
        )
    }
}

@Composable
private fun ScanUndoConfirmation(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_session_undo_title)) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.scan_session_undo_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_cancel)) }
        },
    )
}

@Composable
private fun ScanAttemptOutcome.label(undone: Boolean): String = if (undone) {
    stringResource(R.string.scan_session_result_undone)
} else {
    stringResource(
        when (this) {
            ScanAttemptOutcome.ADDED -> R.string.scan_session_result_added
            ScanAttemptOutcome.DUPLICATE -> R.string.scan_session_result_duplicate
            ScanAttemptOutcome.INVALID -> R.string.scan_session_result_invalid
            ScanAttemptOutcome.NOT_FOUND -> R.string.scan_session_result_not_found
            ScanAttemptOutcome.FAILURE -> R.string.scan_session_result_failure
        },
    )
}

@Composable
internal fun CameraPermissionCard(
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    if (permanentlyDenied) {
                        R.string.camera_permission_permanent
                    } else {
                        R.string.camera_permission_reason
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = if (permanentlyDenied) onOpenSettings else onRequestPermission,
            ) {
                Text(
                    stringResource(
                        if (permanentlyDenied) {
                            R.string.camera_permission_settings
                        } else {
                            R.string.camera_permission_allow
                        },
                    ),
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val CAMERA_PERMISSION_PREFERENCES = "camera-permission"
private const val CAMERA_PERMISSION_REQUESTED = "requested"

@Composable
internal fun ScanResultCard(
    state: ScanUiState,
    onRetry: () -> Unit,
    onCameraRetry: () -> Unit,
    onClear: () -> Unit,
    onAddDuplicateCopy: (String) -> Unit,
) {
    when (state) {
        ScanUiState.Idle -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Text(
                    text = "読み取ると自動で本棚に追加します。登録済みなら重複を知らせます。",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is ScanUiState.Loading -> {
            ResultSurface {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("書誌情報を取得中", fontWeight = FontWeight.SemiBold)
                    Text(
                        state.isbn,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is ScanUiState.Added -> {
            ResultSurface {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("本棚に追加しました", fontWeight = FontWeight.SemiBold)
                    Text(state.title, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.Close, contentDescription = "閉じる")
                }
            }
        }

        is ScanUiState.Duplicate -> {
            var copyLabel by rememberSaveable(state.isbn13) { mutableStateOf("") }
            ResultSurface {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.scan_duplicate_owned, state.copyCount),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(state.title, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = copyLabel,
                        onValueChange = { copyLabel = it.take(100) },
                        label = { Text(stringResource(R.string.scan_duplicate_copy_label)) },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onClear) {
                            Text(stringResource(R.string.scan_duplicate_keep))
                        }
                        Button(onClick = { onAddDuplicateCopy(copyLabel) }) {
                            Text(stringResource(R.string.scan_duplicate_add))
                        }
                    }
                }
            }
        }

        is ScanUiState.Error -> {
            ResultSurface {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = state.message ?: state.failure.message(state.isbn13),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(horizontalAlignment = Alignment.End) {
                    if (state.failure == ScanFailure.CAMERA || state.retryIsbn != null) {
                        TextButton(
                            onClick = if (state.failure == ScanFailure.CAMERA) {
                                onCameraRetry
                            } else {
                                onRetry
                            },
                        ) {
                            Text(stringResource(R.string.scan_retry))
                        }
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Rounded.Close, contentDescription = "閉じる")
                    }
                }
            }
        }
    }
}

@Composable
internal fun BookstoreResultCard(
    state: BookstoreUiState,
    onRetry: () -> Unit,
    onCameraRetry: () -> Unit,
    onClear: () -> Unit,
    onChangeState: (PurchaseTransition) -> Unit,
) {
    when (state) {
        BookstoreUiState.Idle -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                text = stringResource(R.string.bookstore_idle),
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is BookstoreUiState.Loading -> ResultSurface {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.bookstore_loading), fontWeight = FontWeight.SemiBold)
                Text(
                    state.isbn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is BookstoreUiState.Result -> BookstoreBookResult(
            book = state.book,
            busy = false,
            onClear = onClear,
            onChangeState = onChangeState,
        )

        is BookstoreUiState.Updating -> BookstoreBookResult(
            book = state.book,
            busy = true,
            onClear = onClear,
            onChangeState = onChangeState,
        )

        is BookstoreUiState.Error -> ResultSurface {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = state.message ?: state.failure.message(state.isbn13),
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                if (state.failure == ScanFailure.CAMERA || state.retryIsbn != null) {
                    TextButton(
                        onClick = if (state.failure == ScanFailure.CAMERA) {
                            onCameraRetry
                        } else {
                            onRetry
                        },
                    ) {
                        Text(stringResource(R.string.scan_retry))
                    }
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.import_close))
                }
            }
        }
    }
}

@Composable
private fun BookstoreBookResult(
    book: BookstoreBook,
    busy: Boolean,
    onClear: () -> Unit,
    onChangeState: (PurchaseTransition) -> Unit,
) {
    ResultSurface {
        Icon(
            Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        book.primaryAuthor,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClear, enabled = !busy) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.import_close))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column {
                    Text(
                        stringResource(R.string.bookstore_owned_count, book.ownedCopyCount),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.bookstore_owned_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Column {
                    Text(
                        book.purchaseStatus.label(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        stringResource(R.string.bookstore_plan_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onChangeState(PurchaseTransition.WANTED) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.bookstore_wanted)) }
                FilledTonalButton(
                    onClick = { onChangeState(PurchaseTransition.RESERVED) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.bookstore_reserved)) }
            }
            Button(
                onClick = { onChangeState(PurchaseTransition.PURCHASED) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.bookstore_purchased)) }
        }
    }
}

@Composable
private fun WishlistCard(book: BookstoreBook, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(book.purchaseStatus.label(), color = MaterialTheme.colorScheme.tertiary)
            Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(book.primaryAuthor, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.bookstore_list_meta, book.isbn13, book.ownedCopyCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PurchaseStatus?.label(): String = when (this) {
    PurchaseStatus.WANTED -> stringResource(R.string.bookstore_wanted)
    PurchaseStatus.RESERVED -> stringResource(R.string.bookstore_reserved)
    null -> stringResource(R.string.bookstore_not_planned)
}

@Composable
private fun ScanFailure.message(isbn13: String?): String = when (this) {
    ScanFailure.INVALID_ISBN -> stringResource(R.string.scan_error_invalid_isbn)
    ScanFailure.NOT_FOUND -> stringResource(R.string.scan_error_not_found, isbn13.orEmpty())
    ScanFailure.OFFLINE -> stringResource(R.string.scan_error_offline)
    ScanFailure.TIMEOUT -> stringResource(R.string.scan_error_timeout)
    ScanFailure.RATE_LIMITED -> stringResource(R.string.scan_error_rate_limited)
    ScanFailure.SERVICE_UNAVAILABLE -> stringResource(R.string.scan_error_service_unavailable)
    ScanFailure.NETWORK -> stringResource(R.string.scan_error_network)
    ScanFailure.REQUEST_REJECTED -> stringResource(R.string.scan_error_request_rejected)
    ScanFailure.INVALID_RESPONSE -> stringResource(R.string.scan_error_invalid_response)
    ScanFailure.SAVE -> stringResource(R.string.scan_error_save)
    ScanFailure.CAMERA -> stringResource(R.string.scan_error_camera)
}

@Composable
private fun ResultSurface(content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun ManualIsbnEntry(
    isLoading: Boolean,
    onSubmit: (String) -> Unit,
) {
    var isbn by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (isbn.isNotBlank()) {
            onSubmit(isbn)
            isbn = ""
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "カメラが使えないとき",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = isbn,
            onValueChange = { value ->
                isbn = value.filter { character ->
                    character.isDigit() || character == '-' || character == 'X'
                }.take(17)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ISBNを手入力") },
            placeholder = { Text("9784820418078") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Button(
            onClick = submit,
            modifier = Modifier.fillMaxWidth(),
            enabled = isbn.isNotBlank() && !isLoading,
        ) {
            Text("ISBNから登録")
        }
    }
}
