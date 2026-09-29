package com.scanner.app.ui.camera

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.media.ExifInterface
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.app.data.repository.PageRepository
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

    private val _burstModeEnabled = MutableStateFlow(false)
    val burstModeEnabled: StateFlow<Boolean> = _burstModeEnabled.asStateFlow()

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

    fun toggleCurvedMode() {
        val newVal = !_curvedModeEnabled.value
        _curvedModeEnabled.value = newVal
        frameAnalyzer?.curvedMode = newVal
    }

    fun toggleBurstMode() {
        _burstModeEnabled.value = !_burstModeEnabled.value
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

        if (_burstModeEnabled.value) {
            captureBurstPhoto(context, capture, onPageSaved)
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

    private fun captureBurstPhoto(context: Context, capture: ImageCapture, onPageSaved: (String) -> Unit) {
        viewModelScope.launch {
            _isCapturing.value = true
            val control = cameraControl
            val info = cameraInfo
            try {
                // If not currently stable, wait briefly up to 2 seconds for camera to stabilize (SPEC_08)
                if (!_isStable.value) {
                    withTimeoutOrNull(2000L) {
                        _isStable.first { it }
                    }
                }

                // Query hardware exposure capabilities for Dynamic EV Bracketing (SPEC_08 Section 2)
                val exposureState = info?.exposureState
                val minIndex = exposureState?.exposureCompensationRange?.lower ?: 0
                val step = exposureState?.exposureCompensationStep?.let {
                    if (it.denominator != 0) it.numerator.toFloat() / it.denominator.toFloat() else 1.0f
                } ?: 1.0f

                // Target -2.0 EV compensation index to pull bright screen highlights into linear range (SPEC_08)
                val targetHighlightIndex = if (step > 0f) {
                    val calculated = kotlin.math.round(-2.0f / step).toInt()
                    calculated.coerceIn(minIndex, 0)
                } else {
                    minIndex.coerceAtMost(0)
                }

                val tempFiles = mutableListOf<File>()

                // Frame 0: EV = 0 (Base frame: geometry baseline and ambient environment)
                val file0 = File(context.cacheDir, "burst_${UUID.randomUUID()}_0.jpg")
                if (takeSinglePicture(capture, context, file0) && file0.exists() && file0.length() > 0) {
                    normalizeExifOrientation(file0)
                    tempFiles.add(file0)
                }

                // Frame 1: EV = targetHighlightIndex (Highlight frame: unclipped clouds, screens & specular highlights per SPEC_10)
                if (targetHighlightIndex < 0 && control != null) {
                    setExposureIndex(control, context, targetHighlightIndex)
                }
                val file1 = File(context.cacheDir, "burst_${UUID.randomUUID()}_1.jpg")
                if (takeSinglePicture(capture, context, file1) && file1.exists() && file1.length() > 0) {
                    normalizeExifOrientation(file1)
                    tempFiles.add(file1)
                }

                if (tempFiles.isEmpty()) {
                    _isCapturing.value = false
                    return@launch
                }

                val finalPhotoFile = File(context.cacheDir, "${UUID.randomUUID()}.jpg")

                withContext(Dispatchers.IO) {
                    if (tempFiles.size == 1) {
                        tempFiles[0].copyTo(finalPhotoFile, overwrite = true)
                    } else {
                        val mats = mutableListOf<Mat>()
                        for (file in tempFiles) {
                            val mat = Imgcodecs.imread(file.absolutePath)
                            if (!mat.empty()) {
                                mats.add(mat)
                            }
                        }

                        if (mats.size >= 2) {
                            val fusionEngine = NativeBurstFusion()
                            val fusedMat = fusionEngine.fuseBurstFrames(mats, removeGlare = true, isScreenMode = true)
                            if (!fusedMat.empty()) {
                                Imgcodecs.imwrite(finalPhotoFile.absolutePath, fusedMat)
                                fusedMat.release()
                            } else {
                                tempFiles[0].copyTo(finalPhotoFile, overwrite = true)
                            }
                            for (m in mats) {
                                m.release()
                            }
                        } else if (mats.size == 1) {
                            tempFiles[0].copyTo(finalPhotoFile, overwrite = true)
                            mats[0].release()
                        } else {
                            tempFiles[0].copyTo(finalPhotoFile, overwrite = true)
                        }
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
                // Ensure exposure compensation is always restored to baseline EV = 0
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
        var photoW = 0f
        var photoH = 0f
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (degrees != 0f) {
                val origBmp = BitmapFactory.decodeFile(file.absolutePath)
                if (origBmp != null) {
                    val matrix = Matrix().apply { postRotate(degrees) }
                    val rotatedBmp = Bitmap.createBitmap(
                        origBmp, 0, 0, origBmp.width, origBmp.height, matrix, true
                    )
                    FileOutputStream(file).use { out ->
                        rotatedBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    photoW = rotatedBmp.width.toFloat()
                    photoH = rotatedBmp.height.toFloat()
                    if (rotatedBmp != origBmp) {
                        rotatedBmp.recycle()
                    }
                    origBmp.recycle()

                    val resetExif = ExifInterface(file.absolutePath)
                    resetExif.setAttribute(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL.toString()
                    )
                    resetExif.saveAttributes()
                }
            } else {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
                photoW = boundsOpts.outWidth.toFloat()
                photoH = boundsOpts.outHeight.toFloat()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (photoW <= 0f || photoH <= 0f) {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            photoW = boundsOpts.outWidth.toFloat()
            photoH = boundsOpts.outHeight.toFloat()
        }
        return Pair(photoW, photoH)
    }

    private fun getImageDimensions(file: File): Pair<Float, Float> {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        return Pair(boundsOpts.outWidth.toFloat(), boundsOpts.outHeight.toFloat())
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
