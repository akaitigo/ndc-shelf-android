package dev.ndcshelf.app.ui.components

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ndcshelf.app.R
import dev.ndcshelf.app.scanner.IsbnBarcodeAnalyzer
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CameraPreview(
    onIsbnDetected: (String) -> Unit,
    onCameraError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnIsbnDetected = rememberUpdatedState(onIsbnDetected)
    val currentOnCameraError = rememberUpdatedState(onCameraError)
    val currentCameraStartError = rememberUpdatedState(stringResource(R.string.camera_start_error))
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusSequence by remember { mutableIntStateOf(0) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analysisExecutor = remember(lifecycleOwner) { Executors.newSingleThreadExecutor() }
    val analyzer = remember(lifecycleOwner) {
        IsbnBarcodeAnalyzer { isbn -> currentOnIsbnDetected.value(isbn) }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = {
                val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                var downPoint = Offset.Zero
                var pendingClickPoint: Offset? = null
                var scaling = false
                fun focusAt(pointInView: Offset) {
                    val activeCamera = camera ?: return
                    val point = previewView.meteringPointFactory.createPoint(
                        pointInView.x,
                        pointInView.y,
                    )
                    val action = FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                    ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                    if (!activeCamera.cameraInfo.isFocusMeteringSupported(action)) return
                    runCatching { activeCamera.cameraControl.startFocusAndMetering(action) }
                    focusPoint = pointInView
                    focusSequence += 1
                }
                val scaleDetector = ScaleGestureDetector(
                    context,
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            scaling = true
                            val activeCamera = camera ?: return false
                            val zoom = activeCamera.cameraInfo.zoomState.value ?: return false
                            if (zoom.maxZoomRatio <= zoom.minZoomRatio) return false
                            val target = calculateZoomRatio(
                                current = zoom.zoomRatio,
                                scaleFactor = detector.scaleFactor,
                                minimum = zoom.minZoomRatio,
                                maximum = zoom.maxZoomRatio,
                            )
                            activeCamera.cameraControl.setZoomRatio(target)
                            return true
                        }
                    },
                )
                previewView.setOnClickListener {
                    focusAt(
                        pendingClickPoint ?: Offset(
                            previewView.width / 2f,
                            previewView.height / 2f,
                        ),
                    )
                    pendingClickPoint = null
                }
                previewView.setOnTouchListener { view, event ->
                    scaleDetector.onTouchEvent(event)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downPoint = Offset(event.x, event.y)
                            scaling = false
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> scaling = true
                        MotionEvent.ACTION_UP -> {
                            val dx = event.x - downPoint.x
                            val dy = event.y - downPoint.y
                            if (!scaling && dx * dx + dy * dy <= touchSlop * touchSlop) {
                                pendingClickPoint = Offset(event.x, event.y)
                                view.performClick()
                            }
                            scaling = false
                        }
                        MotionEvent.ACTION_CANCEL -> scaling = false
                    }
                    true
                }
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        focusPoint?.let { point ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.7f),
                    radius = 30.dp.toPx(),
                    center = point,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx()),
                )
                drawCircle(
                    color = Color.White,
                    radius = 28.dp.toPx(),
                    center = point,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                )
            }
        }

        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            FilledTonalIconButton(
                onClick = {
                    val next = !torchEnabled
                    camera?.cameraControl?.enableTorch(next)?.addListener(
                        {
                            torchEnabled = camera?.cameraInfo?.torchState?.value == TorchState.ON
                        },
                        ContextCompat.getMainExecutor(context),
                    )
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(
                    imageVector = if (torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    contentDescription = stringResource(
                        if (torchEnabled) R.string.camera_torch_off else R.string.camera_torch_on,
                    ),
                )
            }
        }
    }

    LaunchedEffect(focusSequence) {
        if (focusSequence == 0) return@LaunchedEffect
        delay(1_200)
        focusPoint = null
    }

    DisposableEffect(lifecycleOwner) {
        val disposed = AtomicBoolean(false)
        var imageAnalysis: ImageAnalysis? = null
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                runCatching { camera?.cameraControl?.enableTorch(false) }
                torchEnabled = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        cameraProviderFuture.addListener(
            {
                if (disposed.get()) return@addListener
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }
                    imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, analyzer) }

                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                } catch (_: Exception) {
                    currentOnCameraError.value(
                        currentCameraStartError.value,
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            disposed.set(true)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            runCatching { camera?.cameraControl?.enableTorch(false) }
            torchEnabled = false
            camera = null
            imageAnalysis?.clearAnalyzer()
            if (cameraProviderFuture.isDone) {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
            analyzer.close()
            analysisExecutor.shutdown()
        }
    }
}

internal fun calculateZoomRatio(
    current: Float,
    scaleFactor: Float,
    minimum: Float,
    maximum: Float,
): Float {
    if (!current.isFinite() || !scaleFactor.isFinite() ||
        !minimum.isFinite() || !maximum.isFinite() || minimum <= 0f || maximum < minimum
    ) return current
    return (current * scaleFactor).coerceIn(minimum, maximum)
}
