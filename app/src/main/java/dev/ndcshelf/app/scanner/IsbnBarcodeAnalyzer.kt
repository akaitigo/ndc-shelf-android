package dev.ndcshelf.app.scanner

import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class IsbnBarcodeAnalyzer(
    private val onIsbnDetected: (String) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13)
            .build(),
    )
    private val isProcessing = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    private var lastCandidate: String? = null
    private var consecutiveDetections = 0
    private var lastEmittedAt = 0L

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (isClosed.get()) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null || !isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val input = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees,
        )

        val task = try {
            scanner.process(input)
        } catch (_: Exception) {
            isProcessing.set(false)
            imageProxy.close()
            return
        }
        task
            .addOnSuccessListener { barcodes ->
                if (isClosed.get()) return@addOnSuccessListener
                val isbn = barcodes
                    .asSequence()
                    .mapNotNull(Barcode::getRawValue)
                    .mapNotNull(Isbn::normalizeToIsbn13)
                    .firstOrNull()

                if (isbn == null) {
                    lastCandidate = null
                    consecutiveDetections = 0
                    return@addOnSuccessListener
                }

                if (isbn == lastCandidate) {
                    consecutiveDetections += 1
                } else {
                    lastCandidate = isbn
                    consecutiveDetections = 1
                }

                val now = SystemClock.elapsedRealtime()
                if (
                    consecutiveDetections >= REQUIRED_CONSECUTIVE_DETECTIONS &&
                    now - lastEmittedAt >= EMIT_COOLDOWN_MILLIS
                ) {
                    lastEmittedAt = now
                    onIsbnDetected(isbn)
                }
            }
            .addOnCompleteListener {
                isProcessing.set(false)
                imageProxy.close()
            }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) scanner.close()
    }

    private companion object {
        const val REQUIRED_CONSECUTIVE_DETECTIONS = 2
        const val EMIT_COOLDOWN_MILLIS = 2_000L
    }
}
