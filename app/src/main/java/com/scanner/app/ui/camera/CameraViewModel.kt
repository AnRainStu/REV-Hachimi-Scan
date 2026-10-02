package com.scanner.app.ui.camera

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import androidx.exifinterface.media.ExifInterface
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.app.data.repository.PageRepository
import com.scanner.app.data.util.ExifUtils
import com.scanner.app.domain.model.DetectionResult
import com.scanner.app.domain.model.DocumentQuad
import com.scanner.app.domain.model.ImageFilter
import com.scanner.app.domain.model.ScannedPage
import com.scanner.app.engine.NativeBurstFusion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume

/**
 * CameraViewModel: manages camera state, capture pipeline, and document detection.
 *
 * Capture modes (SPEC_16):
 *   - Normal: single-frame, fast shutter, [Normal] EXIF tag.
 *   - Full HDR: 4-frame pipeline (1 base EV0 + 1 highlight EV-2 + 2 sub-pixel EV0),
 *     producing ~50MP super-resolution output with multi-EV HDR tone mapping. [Full HDR] EXIF tag.
 *
 * Tap-to-focus is handled in CameraScreen via PreviewView.meteringPointFactory; the ViewModel
 * exposes `tapToFocus(x, y, previewWidth, previewHeight)` for decoupled trigger.
 */
class CameraViewModel : ViewModel() {

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    fun reportError(message: String) { _error.value = message }
    fun dismissError() { _error.value = null }

    private val _detectedQuad = MutableStateFlow<DetectionResult?>(null)
    val detectedQuad: StateFlow<DetectionResult?> = _detectedQuad.asStateFlow()

    private val _curvedModeEnabled = MutableStateFlow(false)
    val curvedModeEnabled: StateFlow<Boolean> = _curvedModeEnabled.asStateFlow()

    /** Full HDR: 4-frame (1+3 super-res + multi-EV highlight recovery). SPEC_16 §2. */
    private val _fullHdrEnabled = MutableStateFlow(false)
    val fullHdrEnabled: StateFlow<Boolean> = _fullHdrEnabled.asStateFlow()

    private val _isStable = MutableStateFlow(false)
    val isStable: StateFlow<Boolean> = _isStable.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    val capturedPages: StateFlow<List<ScannedPage>> = PageRepository.pages

    val pageCount: StateFlow<Int> = PageRepository.pages
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    var imageCapture: ImageCapture? = null
    var imageAnalysis: ImageAnalysis? = null
    var cameraControl: CameraControl? = null
    var cameraInfo: CameraInfo? = null
    var frameAnalyzer: com.scanner.app.data.camera.FrameAnalyzer? = null

    fun updateSettings(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _fullHdrEnabled.value = prefs.getBoolean("full_hdr_enabled", false)
    }

    fun toggleCurvedMode() {
        val newVal = !_curvedModeEnabled.value
        _curvedModeEnabled.value = newVal
        frameAnalyzer?.curvedMode = newVal
    }

    fun setTouchPoint(normX: Float, normY: Float) {
        frameAnalyzer?.touchPoint = PointF(normX, normY)
    }

    fun onFrameAnalyzed(result: DetectionResult) {
        val prev = _detectedQuad.value
        val newQuad = result.quad
        val isStableFrame = result.isStable

        _isStable.value = isStableFrame

        if (prev == null || newQuad == null || prev.quad == null) {
            lastQuad = newQuad
            _detectedQuad.value = result
            return
        }

        val smoothedQuad = smoothQuad(prev.quad!!, newQuad)
        lastQuad = smoothedQuad
        _detectedQuad.value = result.copy(quad = smoothedQuad)
    }

    private var lastQuad: DocumentQuad? = null

    private fun smoothQuad(prev: DocumentQuad, newQuad: DocumentQuad): DocumentQuad {
        fun dist(a: PointF, b: PointF) = kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
        val avgDist = listOf(
            dist(prev.topLeft, newQuad.topLeft),
            dist(prev.topRight, newQuad.topRight),
            dist(prev.bottomRight, newQuad.bottomRight),
            dist(prev.bottomLeft, newQuad.bottomLeft)
        ).average().toFloat()

        return if (avgDist > 80f) {
            newQuad
        } else if (avgDist < 2.5f) {
            prev
        } else {
            val alpha = 0.22f
            val beta = 1f - alpha
            DocumentQuad(
                topLeft = PointF(prev.topLeft.x * beta + newQuad.topLeft.x * alpha, prev.topLeft.y * beta + newQuad.topLeft.y * alpha),
                topRight = PointF(prev.topRight.x * beta + newQuad.topRight.x * alpha, prev.topRight.y * beta + newQuad.topRight.y * alpha),
                bottomRight = PointF(prev.bottomRight.x * beta + newQuad.bottomRight.x * alpha, prev.bottomRight.y * beta + newQuad.bottomRight.y * alpha),
                bottomLeft = PointF(prev.bottomLeft.x * beta + newQuad.bottomLeft.x * alpha, prev.bottomLeft.y * beta + newQuad.bottomLeft.y * alpha)
            )
        }
    }

    // ─────────────────────────────────────────────
    // Capture Entry Point
    // ─────────────────────────────────────────────

    fun capturePhoto(context: Context, onPageSaved: (String) -> Unit = {}) {
        if (_isCapturing.value) return
        val capture = imageCapture ?: return

        if (_fullHdrEnabled.value) {
            captureFullHdrPhoto(context, capture, onPageSaved)
        } else {
            captureSinglePhoto(context, capture, onPageSaved)
        }
    }

    // ─────────────────────────────────────────────
    // Normal Single-Frame Capture  (SPEC_16 §3)
    // ─────────────────────────────────────────────

    private fun captureSinglePhoto(context: Context, capture: ImageCapture, onPageSaved: (String) -> Unit) {
        _isCapturing.value = true
        viewModelScope.launch {
            val photoFile = File(com.scanner.app.data.image.ImageStorage(context).getStorageDir(), "${UUID.randomUUID()}.jpg")
            var saved = false
            try {
                check(takeSinglePicture(capture, context, photoFile)) { "Capture failed" }
                val page = withContext(Dispatchers.IO) {
                    val (w, h) = normalizeExifOrientation(photoFile)
                    ExifUtils.stampSignature(photoFile, mode = "Normal")
                    ScannedPage(originalImagePath = photoFile.absolutePath,
                        quad = detectSavedQuad(photoFile, w, h)).also(PageRepository::addPage)
                }
                saved = true
                onPageSaved(page.id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { reportError(e.message ?: "Capture failed") }
            finally { _isCapturing.value = false; if (!saved && PageRepository.pages.value.none { it.originalImagePath == photoFile.absolutePath }) photoFile.delete() }
        }
    }

    // ─────────────────────────────────────────────
    // Full HDR Unified Capture Pipeline  (SPEC_16 §2)
    //
    // Frame sequence:
    //   Frame 0: EV  0   (base, spatial reference + super-res anchor)
    //   Frame 1: EV -2   (highlight recovery)
    //   Frame 2: EV  0   (sub-pixel super-res aux 1)
    //   Frame 3: EV  0   (sub-pixel super-res aux 2)
    //
    // fuseBurstFrames flags: removeGlare=true, isScreenMode=true, superResolution=true
    // Output: ~50MP JPEG @ quality 95, EXIF mode = "Full HDR"
    // ─────────────────────────────────────────────

    private fun captureFullHdrPhoto(context: Context, capture: ImageCapture, onPageSaved: (String) -> Unit) {
        viewModelScope.launch {
            _isCapturing.value = true
            val control = cameraControl
            val info = cameraInfo
            val tempFiles = mutableListOf<File>()
            val finalPhotoFile = File(com.scanner.app.data.image.ImageStorage(context).getStorageDir(), "${UUID.randomUUID()}.jpg")
            var saved = false
            try {
                // Wait for stable frame before burst (up to 1.5s)
                if (!_isStable.value) {
                    withTimeoutOrNull(1500L) {
                        _isStable.first { it }
                    }
                }

                // Resolve highlight-recovery EV index (target ≈ -2 EV, clamped to sensor range)
                val exposureState = info?.exposureState
                val minIndex = exposureState?.exposureCompensationRange?.lower ?: 0
                val step = exposureState?.exposureCompensationStep?.let {
                    if (it.denominator != 0) it.numerator.toFloat() / it.denominator.toFloat() else 1.0f
                } ?: 1.0f
                val targetHighlightIndex = if (step > 0f) {
                    kotlin.math.round(-2.0f / step).toInt().coerceIn(minIndex, 0)
                } else {
                    minIndex.coerceAtMost(0)
                }


                // Frame 0: EV 0 — base frame
                val file0 = File(context.cacheDir, "hdr_${UUID.randomUUID()}_0.jpg")
                tempFiles.add(file0)
                check(takeSinglePicture(capture, context, file0) && file0.length() > 0) { "HDR frame 1 failed" }

                // Frame 1: EV targetHighlightIndex — highlight recovery
                if (targetHighlightIndex < 0 && control != null) {
                    setExposureIndex(control, context, targetHighlightIndex)
                    delay(50)
                }
                val file1 = File(context.cacheDir, "hdr_${UUID.randomUUID()}_1.jpg")
                tempFiles.add(file1)
                check(takeSinglePicture(capture, context, file1) && file1.length() > 0) { "HDR frame 2 failed" }

                // Frame 2: EV 0 — sub-pixel aux 1 (restore AE before firing)
                if (targetHighlightIndex < 0 && control != null) {
                    setExposureIndex(control, context, 0)
                    delay(50)
                }
                val file2 = File(context.cacheDir, "hdr_${UUID.randomUUID()}_2.jpg")
                tempFiles.add(file2)
                check(takeSinglePicture(capture, context, file2) && file2.length() > 0) { "HDR frame 3 failed" }

                // Frame 3: EV 0 — sub-pixel aux 2
                val file3 = File(context.cacheDir, "hdr_${UUID.randomUUID()}_3.jpg")
                tempFiles.add(file3)
                check(takeSinglePicture(capture, context, file3) && file3.length() > 0) { "HDR frame 4 failed" }

                if (tempFiles.isEmpty()) {
                    _isCapturing.value = false
                    return@launch
                }


                withContext(Dispatchers.IO) {
                    val mats = mutableListOf<Mat>()
                    try {
                    for (file in tempFiles) {
                        val mat = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
                        if (!mat.empty()) mats.add(mat) else mat.release()
                    }

                    if (mats.isNotEmpty()) {
                        val fusionEngine = NativeBurstFusion()
                        val fusedMat = fusionEngine.fuseBurstFrames(
                            burstFrames = mats,
                            removeGlare = true,
                            isScreenMode = true,   // HDR highlight graft + shadow S-curve
                            superResolution = true  // 2× canvas → ~50MP
                        )
                        if (!fusedMat.empty()) {
                            rotateAndSaveFusedMat(fusedMat, tempFiles[0], finalPhotoFile, mode = "Full HDR", jpegQuality = 95)
                        } else {
                            tempFiles[0].copyTo(finalPhotoFile, overwrite = true)
                            normalizeExifOrientation(finalPhotoFile)
                            ExifUtils.stampSignature(finalPhotoFile, mode = "Full HDR")
                        }

                    } else {
                        tempFiles[0].copyTo(finalPhotoFile, overwrite = true)
                        normalizeExifOrientation(finalPhotoFile)
                        ExifUtils.stampSignature(finalPhotoFile, mode = "Full HDR")
                    }

                    } finally { mats.forEach { it.release() } }
                }

                val (photoW, photoH) = getImageDimensions(finalPhotoFile)
                check(photoW > 0f && photoH > 0f) { "HDR output is unreadable" }
                val currentResult = _detectedQuad.value
                val finalQuad = withContext(Dispatchers.IO) { detectSavedQuad(finalPhotoFile, photoW, photoH) }

                val newPage = ScannedPage(
                    id = UUID.randomUUID().toString(),
                    originalImagePath = finalPhotoFile.absolutePath,
                    quad = finalQuad,
                    filter = ImageFilter.MAGIC_COLOR
                )
                withContext(Dispatchers.IO) { PageRepository.addPage(newPage) }
                saved = true

                withContext(Dispatchers.Main) {
                    _isCapturing.value = false
                    frameAnalyzer?.resetStability()
                    onPageSaved(newPage.id)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                reportError(e.message ?: "HDR capture failed")
            } finally {
                _isCapturing.value = false
                tempFiles.forEach { it.delete() }
                if (!saved && PageRepository.pages.value.none { it.originalImagePath == finalPhotoFile.absolutePath }) finalPhotoFile.delete()
                control?.setExposureCompensationIndex(0)
            }
        }
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    /**
     * Read EXIF orientation from [refFile], rotate [fusedMat] using OpenCV SIMD rotate,
     * write JPEG at [jpegQuality]%, then stamp EXIF provenance from [refFile].
     * This avoids the expensive Java Bitmap decode/re-encode path for rotation.
     */
    private fun rotateAndSaveFusedMat(
        fusedMat: Mat,
        refFile: File,
        outFile: File,
        mode: String,
        jpegQuality: Int = 95
    ) {
        val orientation = try {
            ExifInterface(refFile.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val uprightMat = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> {
                val r = Mat(); Core.rotate(fusedMat, r, Core.ROTATE_90_CLOCKWISE); fusedMat.release(); r
            }
            ExifInterface.ORIENTATION_ROTATE_180 -> {
                val r = Mat(); Core.rotate(fusedMat, r, Core.ROTATE_180); fusedMat.release(); r
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> {
                val r = Mat(); Core.rotate(fusedMat, r, Core.ROTATE_90_COUNTERCLOCKWISE); fusedMat.release(); r
            }
            else -> fusedMat
        }

        val saveParams = org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality)
        try {
            check(Imgcodecs.imwrite(outFile.absolutePath, uprightMat, saveParams)) { "Cannot save HDR image" }
            ExifUtils.copyAndStampExif(refFile, outFile, mode = mode)
        } finally { saveParams.release(); uprightMat.release() }
    }

    private suspend fun setExposureIndex(
        control: CameraControl,
        context: Context,
        index: Int
    ): Int = suspendCancellableCoroutine { continuation ->
        try {
            val future = control.setExposureCompensationIndex(index)
            val executor = ContextCompat.getMainExecutor(context)
            future.addListener({
                try {
                    val result = future.get()
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(index)
                }
            }, executor)
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(index)
        }
    }

    private suspend fun takeSinglePicture(
        capture: ImageCapture,
        context: Context,
        targetFile: File
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val outputOptions = ImageCapture.OutputFileOptions.Builder(targetFile).build()
        val executor = ContextCompat.getMainExecutor(context)
        capture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        )
    }

    /**
     * Normalise EXIF orientation in-place using Java Bitmap (used for single-frame Normal captures).
     * For fused multi-frame output, use [rotateAndSaveFusedMat] instead to avoid full JPEG re-decode.
     */
    private fun normalizeExifOrientation(file: File): Pair<Float, Float> {
        val mat = Imgcodecs.imread(file.absolutePath) // OpenCV applies all EXIF orientations.
        val normalized = File(file.parentFile, "normalized_${UUID.randomUUID()}.jpg")
        val params = org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95)
        try {
            check(!mat.empty()) { "Image cannot be decoded" }
            val dimensions = mat.cols().toFloat() to mat.rows().toFloat()
            check(Imgcodecs.imwrite(normalized.absolutePath, mat, params)) { "Cannot save image" }
            ExifUtils.copyAndStampExif(file, normalized)
            java.nio.file.Files.move(normalized.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return dimensions
        } finally { mat.release(); params.release(); normalized.delete() }
    }

    private fun getImageDimensions(file: File): Pair<Float, Float> {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        return Pair(boundsOpts.outWidth.toFloat(), boundsOpts.outHeight.toFloat())
    }

    private fun detectSavedQuad(file: File, width: Float, height: Float): DocumentQuad {
        val gray = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        try {
            val result = com.scanner.app.engine.NativeEdgeDetector().detectDocument(gray, false)
            return result.quad?.takeIf { result.found && it.isValid() } ?: computeTargetQuad(null, width, height)
        } finally { gray.release() }
    }

    private fun computeTargetQuad(currentResult: DetectionResult?, photoW: Float, photoH: Float): DocumentQuad {
        return if (currentResult?.found == true && currentResult.quad != null && currentResult.frameWidth > 0 && currentResult.frameHeight > 0) {
            val sx = photoW / currentResult.frameWidth.toFloat()
            val sy = photoH / currentResult.frameHeight.toFloat()
            val q = currentResult.quad
            DocumentQuad(
                topLeft = PointF((q.topLeft.x * sx).coerceIn(0f, photoW), (q.topLeft.y * sy).coerceIn(0f, photoH)),
                topRight = PointF((q.topRight.x * sx).coerceIn(0f, photoW), (q.topRight.y * sy).coerceIn(0f, photoH)),
                bottomRight = PointF((q.bottomRight.x * sx).coerceIn(0f, photoW), (q.bottomRight.y * sy).coerceIn(0f, photoH)),
                bottomLeft = PointF((q.bottomLeft.x * sx).coerceIn(0f, photoW), (q.bottomLeft.y * sy).coerceIn(0f, photoH))
            )
        } else {
            val insetX = 0f
            val insetY = 0f
            DocumentQuad(
                topLeft = PointF(insetX, insetY),
                topRight = PointF(photoW - insetX, insetY),
                bottomRight = PointF(photoW - insetX, photoH - insetY),
                bottomLeft = PointF(insetX, photoH - insetY)
            )
        }
    }

    fun importFromUri(context: Context, uri: Uri, onPageSaved: (String) -> Unit) {
        if (_isCapturing.value) return
        _isCapturing.value = true
        viewModelScope.launch {
            val photoFile = File(com.scanner.app.data.image.ImageStorage(context).getStorageDir(), "${UUID.randomUUID()}.jpg")
            var saved = false
            try {
                val page = withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open image" }.use { input ->
                        photoFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    val (w, h) = normalizeExifOrientation(photoFile)
                    val gray = Imgcodecs.imread(photoFile.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
                    val detected = try { com.scanner.app.engine.NativeEdgeDetector().detectDocument(gray, false) }
                        finally { gray.release() }
                    val quad = detected.quad?.takeIf { detected.found && it.isValid() } ?: computeTargetQuad(null, w, h)
                    ScannedPage(originalImagePath = photoFile.absolutePath, quad = quad).also(PageRepository::addPage)
                }
                saved = true
                onPageSaved(page.id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { reportError(e.message ?: "Image import failed") }
            finally { _isCapturing.value = false; if (!saved && PageRepository.pages.value.none { it.originalImagePath == photoFile.absolutePath }) photoFile.delete() }
        }
    }
}
