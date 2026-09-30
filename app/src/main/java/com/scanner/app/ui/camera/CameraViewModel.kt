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
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume

class CameraViewModel : ViewModel() {

    private val _detectedQuad = MutableStateFlow<DetectionResult?>(null)
    val detectedQuad: StateFlow<DetectionResult?> = _detectedQuad.asStateFlow()

    private val _curvedModeEnabled = MutableStateFlow(false)
    val curvedModeEnabled: StateFlow<Boolean> = _curvedModeEnabled.asStateFlow()

    private val _burstSuperResEnabled = MutableStateFlow(false)
    val burstSuperResEnabled: StateFlow<Boolean> = _burstSuperResEnabled.asStateFlow()

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
        _burstSuperResEnabled.value = prefs.getBoolean("burst_super_res_enabled", false)
    }

    fun toggleCurvedMode() {
        val newVal = !_curvedModeEnabled.value
        _curvedModeEnabled.value = newVal
        frameAnalyzer?.curvedMode = newVal
    }

    fun setTouchPoint(normX: Float, normY: Float) {
        frameAnalyzer?.touchPoint = PointF(normX, normY)
        viewModelScope.launch {
            delay(2000L)
            if (frameAnalyzer?.touchPoint?.x == normX && frameAnalyzer?.touchPoint?.y == normY) {
                frameAnalyzer?.touchPoint = null
            }
        }
    }

    private var lastQuad: DocumentQuad? = null
    private var candidateQuad: DocumentQuad? = null
    private var candidateFrames = 0
    private var missedFrames = 0
    private val maxMissedFrames = 5

    fun onFrameAnalyzed(result: DetectionResult) {
        _isStable.value = result.isStable

        if (!result.found || result.quad == null) {
            missedFrames++
            candidateQuad = null
            candidateFrames = 0
            if (missedFrames > maxMissedFrames) {
                lastQuad = null
                _detectedQuad.value = result
            }
            return
        }

        missedFrames = 0
        val newQuad = result.quad
        val prev = lastQuad

        if (prev == null) {
            // Require 2 consecutive frames before establishing a new lock to filter out transient flashes
            if (candidateQuad == null) {
                candidateQuad = newQuad
                candidateFrames = 1
                return
            } else {
                candidateFrames++
                if (candidateFrames < 2) return
                // Candidate confirmed
                lastQuad = newQuad
                candidateQuad = null
                candidateFrames = 0
                _detectedQuad.value = result.copy(quad = newQuad)
                return
            }
        }

        val d0 = kotlin.math.hypot(prev.topLeft.x - newQuad.topLeft.x, prev.topLeft.y - newQuad.topLeft.y)
        val d1 = kotlin.math.hypot(prev.topRight.x - newQuad.topRight.x, prev.topRight.y - newQuad.topRight.y)
        val d2 = kotlin.math.hypot(prev.bottomRight.x - newQuad.bottomRight.x, prev.bottomRight.y - newQuad.bottomRight.y)
        val d3 = kotlin.math.hypot(prev.bottomLeft.x - newQuad.bottomLeft.x, prev.bottomLeft.y - newQuad.bottomLeft.y)
        val avgDist = (d0 + d1 + d2 + d3) / 4f

        val smoothedQuad = if (avgDist > 120f) {
            // Major movement: update immediately
            newQuad
        } else if (avgDist < 2.5f) {
            // Micro-tremor deadband
            prev
        } else {
            // Exponential moving average (smooth tracking)
            val alpha = 0.22f
            val beta = 1f - alpha
            DocumentQuad(
                topLeft = PointF(prev.topLeft.x * beta + newQuad.topLeft.x * alpha, prev.topLeft.y * beta + newQuad.topLeft.y * alpha),
                topRight = PointF(prev.topRight.x * beta + newQuad.topRight.x * alpha, prev.topRight.y * beta + newQuad.topRight.y * alpha),
                bottomRight = PointF(prev.bottomRight.x * beta + newQuad.bottomRight.x * alpha, prev.bottomRight.y * beta + newQuad.bottomRight.y * alpha),
                bottomLeft = PointF(prev.bottomLeft.x * beta + newQuad.bottomLeft.x * alpha, prev.bottomLeft.y * beta + newQuad.bottomLeft.y * alpha)
            )
        }

        lastQuad = smoothedQuad
        _detectedQuad.value = result.copy(quad = smoothedQuad)
    }

    fun capturePhoto(context: Context, onPageSaved: (String) -> Unit = {}) {
        if (_isCapturing.value) return
        val capture = imageCapture ?: return

        if (_burstSuperResEnabled.value) {
            captureBurstSuperResPhoto(context, capture, onPageSaved)
        } else {
            captureSinglePhoto(context, capture, onPageSaved)
        }
    }

    private fun captureSinglePhoto(context: Context, capture: ImageCapture, onPageSaved: (String) -> Unit) {
        _isCapturing.value = true
        val photoFile = File(context.cacheDir, "${UUID.randomUUID()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        val executor = ContextCompat.getMainExecutor(context)

        capture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    viewModelScope.launch(Dispatchers.Default) {
                        val (photoW, photoH) = normalizeExifOrientation(photoFile)
                        ExifUtils.stampSignature(photoFile)
                        val currentResult = _detectedQuad.value
                        val finalQuad = computeTargetQuad(currentResult, photoW, photoH)

                        val newPage = ScannedPage(
                            id = UUID.randomUUID().toString(),
                            originalImagePath = photoFile.absolutePath,
                            quad = finalQuad,
                            filter = ImageFilter.MAGIC_COLOR
                        )
                        PageRepository.addPage(newPage)
                        withContext(Dispatchers.Main) {
                            _isCapturing.value = false
                            onPageSaved(newPage.id)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    _isCapturing.value = false
                }
            }
        )
    }

    private fun captureBurstSuperResPhoto(context: Context, capture: ImageCapture, onPageSaved: (String) -> Unit) {
        viewModelScope.launch {
            _isCapturing.value = true
            val control = cameraControl
            val info = cameraInfo
            try {
                // If not currently stable, wait briefly up to 1.5 seconds for camera to stabilize
                if (!_isStable.value) {
                    withTimeoutOrNull(1500L) {
                        _isStable.first { it }
                    }
                }

                val tempFiles = mutableListOf<File>()

                // Frame 0..3: Rapid 4-frame burst for subpixel super-resolution (all at native EV = 0)
                for (i in 0 until 4) {
                    val file = File(context.cacheDir, "burst_${UUID.randomUUID()}_$i.jpg")
                    if (takeSinglePicture(capture, context, file) && file.exists() && file.length() > 0) {
                        normalizeExifOrientation(file)
                        tempFiles.add(file)
                    }
                }

                if (tempFiles.isEmpty()) {
                    _isCapturing.value = false
                    return@launch
                }

                val finalPhotoFile = File(context.cacheDir, "${UUID.randomUUID()}.jpg")

                withContext(Dispatchers.IO) {
                    val mats = mutableListOf<Mat>()
                    for (file in tempFiles) {
                        val mat = Imgcodecs.imread(file.absolutePath)
                        if (!mat.empty()) {
                            mats.add(mat)
                        }
                    }

                    if (mats.isNotEmpty()) {
                        val fusionEngine = NativeBurstFusion()
                        // Call 1-to-4 50MP super-resolution
                        val fusedMat = fusionEngine.fuseBurstFrames(
                            burstFrames = mats,
                            removeGlare = true,
                            isScreenMode = false,
                            superResolution = true
                        )
                        if (!fusedMat.empty()) {
                            val saveParams = MatOfInt(
                                Imgcodecs.IMWRITE_JPEG_QUALITY, 100,
                                Imgcodecs.IMWRITE_JPEG_OPTIMIZE, 1
                            )
                            Imgcodecs.imwrite(finalPhotoFile.absolutePath, fusedMat, saveParams)
                            saveParams.release()
                            fusedMat.release()
                            ExifUtils.copyAndStampExif(tempFiles[0], finalPhotoFile)
                        } else {
                            tempFiles[0].copyTo(finalPhotoFile, overwrite = true)
                            ExifUtils.stampSignature(finalPhotoFile)
                        }
                        for (m in mats) {
                            m.release()
                        }
                    } else {
                        tempFiles[0].copyTo(finalPhotoFile, overwrite = true)
                        ExifUtils.stampSignature(finalPhotoFile)
                    }

                    // Clean up temporary burst files
                    for (f in tempFiles) {
                        f.delete()
                    }
                }

                val (photoW, photoH) = getImageDimensions(finalPhotoFile)
                val currentResult = _detectedQuad.value
                val finalQuad = computeTargetQuad(currentResult, photoW, photoH)

                val newPage = ScannedPage(
                    id = UUID.randomUUID().toString(),
                    originalImagePath = finalPhotoFile.absolutePath,
                    quad = finalQuad,
                    filter = ImageFilter.MAGIC_COLOR
                )
                PageRepository.addPage(newPage)

                withContext(Dispatchers.Main) {
                    _isCapturing.value = false
                    frameAnalyzer?.resetStability()
                    onPageSaved(newPage.id)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isCapturing.value = false
            } finally {
                control?.setExposureCompensationIndex(0)
            }
        }
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

    private fun normalizeExifOrientation(file: File): Pair<Float, Float> {
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (rotationDegrees != 0) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    val rotatedBitmap = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )
                    FileOutputStream(file).use { out ->
                        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    }
                    val newExif = ExifInterface(file.absolutePath)
                    newExif.setAttribute(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL.toString()
                    )
                    ExifUtils.stampSignature(newExif)
                    newExif.saveAttributes()
                    if (rotatedBitmap != bitmap) {
                        bitmap.recycle()
                    }
                    val w = rotatedBitmap.width.toFloat()
                    val h = rotatedBitmap.height.toFloat()
                    rotatedBitmap.recycle()
                    return Pair(w, h)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        return Pair(boundsOpts.outWidth.toFloat(), boundsOpts.outHeight.toFloat())
    }

    private fun getImageDimensions(file: File): Pair<Float, Float> {
        return normalizeExifOrientation(file)
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
            val insetX = photoW * 0.08f
            val insetY = photoH * 0.08f
            DocumentQuad(
                topLeft = PointF(insetX, insetY),
                topRight = PointF(photoW - insetX, insetY),
                bottomRight = PointF(photoW - insetX, photoH - insetY),
                bottomLeft = PointF(insetX, photoH - insetY)
            )
        }
    }

    fun importFromUri(context: Context, uri: Uri, onPageSaved: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val photoFile = File(context.cacheDir, "${java.util.UUID.randomUUID()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    photoFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (photoFile.exists() && photoFile.length() > 0) {
                    val (photoW, photoH) = getImageDimensions(photoFile)
                    val mat = Imgcodecs.imread(photoFile.absolutePath)
                    val detector = com.scanner.app.engine.NativeEdgeDetector()
                    val detected = if (!mat.empty()) detector.detectDocument(mat, false) else null
                    mat.release()

                    val targetQuad = if (detected?.found == true && detected.quad != null) {
                        detected.quad
                    } else {
                        computeTargetQuad(null, photoW, photoH)
                    }

                    val page = ScannedPage(
                        originalImagePath = photoFile.absolutePath,
                        quad = targetQuad
                    )
                    PageRepository.addPage(page)
                    withContext(Dispatchers.Main) {
                        onPageSaved(page.id)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
