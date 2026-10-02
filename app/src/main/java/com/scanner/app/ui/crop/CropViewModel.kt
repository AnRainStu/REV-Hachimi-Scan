package com.scanner.app.ui.crop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.app.data.repository.PageRepository
import com.scanner.app.data.util.ExifUtils
import com.scanner.app.domain.model.DocumentQuad
import com.scanner.app.domain.model.ImageFilter
import com.scanner.app.domain.model.ScannedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import com.scanner.app.R
import com.scanner.app.domain.model.AspectRatioPreset
import java.io.File

class CropViewModel : ViewModel() {

    private var currentPageId: String? = null
    private var sourceRotation = 0
    private var draftFile: File? = null
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    fun dismissError() { _error.value = null }

    private val _imagePath = MutableStateFlow<String?>(null)
    val imagePath: StateFlow<String?> = _imagePath.asStateFlow()

    private val _selectedRatio = MutableStateFlow(AspectRatioPreset.A4)
    val selectedRatio: StateFlow<AspectRatioPreset> = _selectedRatio.asStateFlow()

    private val _customRatioValue = MutableStateFlow<Float?>(null)
    val customRatioValue: StateFlow<Float?> = _customRatioValue.asStateFlow()

    private val _currentQuad = MutableStateFlow(
        DocumentQuad(
            topLeft = PointF(100f, 100f),
            topRight = PointF(900f, 100f),
            bottomRight = PointF(900f, 1600f),
            bottomLeft = PointF(100f, 1600f)
        )
    )
    val currentQuad: StateFlow<DocumentQuad> = _currentQuad.asStateFlow()

    private val _selectedFilter = MutableStateFlow(ImageFilter.ORIGINAL)
    val selectedFilter: StateFlow<ImageFilter> = _selectedFilter.asStateFlow()

    private val _imageVersion = MutableStateFlow(0)
    val imageVersion: StateFlow<Int> = _imageVersion.asStateFlow()

    private val _detectedQuad = MutableStateFlow<DocumentQuad?>(null)
    val detectedQuad: StateFlow<DocumentQuad?> = _detectedQuad.asStateFlow()

    // Structural LSD line segments (SPEC_06 §2)
    private val _horizontalLines = MutableStateFlow(FloatArray(0))
    val horizontalLines: StateFlow<FloatArray> = _horizontalLines.asStateFlow()

    private val _verticalLines = MutableStateFlow(FloatArray(0))
    val verticalLines: StateFlow<FloatArray> = _verticalLines.asStateFlow()

    private var lsdJob: Job? = null

    fun onBitmapLoaded(bitmap: Bitmap) {
        lsdJob?.cancel()
        lsdJob = viewModelScope.launch {
            try {
                val (hLines, vLines) = withContext(Dispatchers.Default) {
                    com.scanner.app.engine.NativeEdgeDetector().detectStructuralLines(bitmap)
                }
                _horizontalLines.value = hLines
                _verticalLines.value = vLines
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun tapToSnap(touchX: Float, touchY: Float) {
        val path = _imagePath.value ?: return
        viewModelScope.launch {
            try {
                val quad = withContext(Dispatchers.Default) {
                    val gray = Imgcodecs.imread(path, Imgcodecs.IMREAD_GRAYSCALE)
                    try { if (gray.empty()) null else com.scanner.app.engine.NativeEdgeDetector().findContourAtPointAddr(gray.nativeObjAddr, touchX, touchY) }
                    finally { gray.release() }
                }
                if (quad != null && quad.isValid()) _currentQuad.value = quad
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.message }
        }
    }

    fun loadPage(pageId: String) {
        if (currentPageId == pageId) return
        currentPageId = pageId
        val page = PageRepository.getPage(pageId) ?: return
        val savedRatio = page.targetAspectRatio
        val matchedPreset = AspectRatioPreset.fromRatio(savedRatio)
        _selectedRatio.value = matchedPreset
        if (matchedPreset == AspectRatioPreset.CUSTOM) {
            _customRatioValue.value = savedRatio
        } else {
            _customRatioValue.value = null
        }
        sourceRotation = page.sourceRotation
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val target = File(File(page.originalImagePath).parentFile, "draft_${java.util.UUID.randomUUID()}.jpg")
                    try {
                        if (sourceRotation == 0) File(page.originalImagePath).copyTo(target)
                        else {
                            val mat = Imgcodecs.imread(page.originalImagePath)
                            try {
                                check(!mat.empty()) { "Cannot read original image" }
                                Core.rotate(mat, mat, when (sourceRotation) {
                                    90 -> Core.ROTATE_90_CLOCKWISE
                                    180 -> Core.ROTATE_180
                                    else -> Core.ROTATE_90_COUNTERCLOCKWISE
                                })
                                check(Imgcodecs.imwrite(target.absolutePath, mat)) { "Cannot prepare crop preview" }
                            } finally { mat.release() }
                        }
                        target
                    } catch (e: Throwable) { target.delete(); throw e }
                }
                draftFile?.delete(); draftFile = file; _imagePath.value = file.absolutePath
                if (page.quad == null) resetToFullImage()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.message }
        }
        _selectedFilter.value = page.filter
        page.quad?.let {
            _currentQuad.value = it
            _detectedQuad.value = it
        }
    }

    fun setAspectRatio(preset: AspectRatioPreset) {
        _selectedRatio.value = preset
        if (preset != AspectRatioPreset.CUSTOM) {
            _customRatioValue.value = null
        }
    }

    fun setCustomRatio(ratio: Float) {
        _customRatioValue.value = ratio
        _selectedRatio.value = AspectRatioPreset.CUSTOM
    }

    fun updateQuad(quad: DocumentQuad) {
        _currentQuad.value = quad
    }

    fun updateCorner(cornerIndex: Int, newPosition: PointF) {
        val current = _currentQuad.value
        val newQuad = when (cornerIndex) {
            0 -> DocumentQuad(newPosition, current.topRight, current.bottomRight, current.bottomLeft)
            1 -> DocumentQuad(current.topLeft, newPosition, current.bottomRight, current.bottomLeft)
            2 -> DocumentQuad(current.topLeft, current.topRight, newPosition, current.bottomLeft)
            3 -> DocumentQuad(current.topLeft, current.topRight, current.bottomRight, newPosition)
            else -> current
        }
        _currentQuad.value = newQuad
    }

    fun updateEdge(edgeIndex: Int, deltaX: Float, deltaY: Float) {
        val current = _currentQuad.value
        val newQuad = when (edgeIndex) {
            0 -> DocumentQuad(
                topLeft = PointF(current.topLeft.x + deltaX, current.topLeft.y + deltaY),
                topRight = PointF(current.topRight.x + deltaX, current.topRight.y + deltaY),
                bottomRight = current.bottomRight,
                bottomLeft = current.bottomLeft
            )
            1 -> DocumentQuad(
                topLeft = current.topLeft,
                topRight = PointF(current.topRight.x + deltaX, current.topRight.y + deltaY),
                bottomRight = PointF(current.bottomRight.x + deltaX, current.bottomRight.y + deltaY),
                bottomLeft = current.bottomLeft
            )
            2 -> DocumentQuad(
                topLeft = current.topLeft,
                topRight = current.topRight,
                bottomRight = PointF(current.bottomRight.x + deltaX, current.bottomRight.y + deltaY),
                bottomLeft = PointF(current.bottomLeft.x + deltaX, current.bottomLeft.y + deltaY)
            )
            3 -> DocumentQuad(
                topLeft = PointF(current.topLeft.x + deltaX, current.topLeft.y + deltaY),
                topRight = current.topRight,
                bottomRight = current.bottomRight,
                bottomLeft = PointF(current.bottomLeft.x + deltaX, current.bottomLeft.y + deltaY)
            )
            else -> current
        }
        _currentQuad.value = newQuad
    }

    fun resetToFullImage() {
        val path = _imagePath.value ?: return
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOpts)
        val w = boundsOpts.outWidth.toFloat()
        val h = boundsOpts.outHeight.toFloat()
        if (w > 0 && h > 0) {
            _currentQuad.value = DocumentQuad(
                topLeft = PointF(0f, 0f),
                topRight = PointF(w, 0f),
                bottomRight = PointF(w, h),
                bottomLeft = PointF(0f, h)
            )
        }
    }

    fun rotateImage(onComplete: () -> Unit = {}) {
        val path = _imagePath.value ?: run { onComplete(); return }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val bmp = android.graphics.BitmapFactory.decodeFile(path)
                if (bmp != null) {
                    val origW = bmp.width.toFloat()
                    val origH = bmp.height.toFloat()
                    val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                    val rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                    java.io.FileOutputStream(path).use { out ->
                        rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    if (rotated != bmp) {
                        rotated.recycle()
                    }
                    bmp.recycle()

                    val resetExif = android.media.ExifInterface(path)
                    resetExif.setAttribute(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL.toString()
                    )
                    resetExif.saveAttributes()

                    // Map corners for 90 CW rotation: (x, y) in [origW, origH] -> (origH - y, x) in [origH, origW]
                    fun rotPt(p: PointF) = PointF(origH - p.y, p.x)
                    val q = _currentQuad.value
                    val newQuad = DocumentQuad(
                        topLeft = rotPt(q.bottomLeft),
                        topRight = rotPt(q.topLeft),
                        bottomRight = rotPt(q.topRight),
                        bottomLeft = rotPt(q.bottomRight)
                    )

                    withContext(Dispatchers.Main) {
                        sourceRotation = (sourceRotation + 90) % 360
                        _detectedQuad.value = null
                        _horizontalLines.value = FloatArray(0)
                        _verticalLines.value = FloatArray(0)
                        _currentQuad.value = newQuad
                        _imageVersion.value++
                        onComplete()
                    }
                    return@launch
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun reDetect() {
        _selectedRatio.value = AspectRatioPreset.CUSTOM
        _customRatioValue.value = null
        val path = _imagePath.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val mat = Imgcodecs.imread(path)
            if (!mat.empty()) {
                val gray = Mat()
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
                val detector = com.scanner.app.engine.NativeEdgeDetector()
                val result = detector.detectDocument(gray, false)
                if (result.found && result.quad != null) {
                    _currentQuad.value = result.quad
                    _detectedQuad.value = result.quad
                }
                gray.release()
                mat.release()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        lsdJob?.cancel()
        draftFile?.delete()
    }

    fun setFilter(filter: ImageFilter) {
        _selectedFilter.value = filter
    }

    private var isSaving = false
    fun confirmCrop(onDone: (Boolean) -> Unit = {}) {
        if (isSaving || _imagePath.value == null) { onDone(false); return }
        val id = currentPageId ?: run { onDone(false); return }
        val quad = _currentQuad.value
        val filter = _selectedFilter.value
        val ratio = if (_selectedRatio.value == AspectRatioPreset.CUSTOM) _customRatioValue.value else _selectedRatio.value.ratio
        val rotation = sourceRotation
        isSaving = true
        viewModelScope.launch {
            var success = false
            try {
                check(PageRepository.getPage(id) != null) { "Page no longer exists" }
                com.scanner.app.data.image.PageEditor.edit(id) {
                    it.copy(quad = quad, filter = filter, targetAspectRatio = ratio, sourceRotation = rotation)
                }
                success = true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.message ?: "Crop failed" }
            finally { isSaving = false; onDone(success) }
        }
    }
}
