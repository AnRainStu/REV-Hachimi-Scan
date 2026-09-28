package com.scanner.app.ui.crop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.app.data.repository.PageRepository
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
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import com.scanner.app.R
import java.io.File

enum class AspectRatioPreset(
    val titleRes: Int,
    val ratio: Float?
) {
    FREE(R.string.ratio_free, null),
    A4(R.string.ratio_a4, 210f / 297f),
    LETTER(R.string.ratio_letter, 8.5f / 11f),
    LEGAL(R.string.ratio_legal, 8.5f / 14f),
    ID_CARD(R.string.ratio_id_card, 85.6f / 53.98f),
    BUSINESS_CARD(R.string.ratio_business_card, 90f / 54f),
    SQUARE(R.string.ratio_square, 1.0f),
    RATIO_4_3(R.string.ratio_4_3, 4f / 3f),
    RATIO_16_9(R.string.ratio_16_9, 16f / 9f)
}

class CropViewModel : ViewModel() {

    private var currentPageId: String? = null

    private val _imagePath = MutableStateFlow<String?>(null)
    val imagePath: StateFlow<String?> = _imagePath.asStateFlow()

    private val _selectedRatio = MutableStateFlow(AspectRatioPreset.FREE)
    val selectedRatio: StateFlow<AspectRatioPreset> = _selectedRatio.asStateFlow()

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

    private var grayMat: Mat? = null
    private var edgeMat: Mat? = null
    private var edgeJob: Job? = null
    private val _grayMatAddr = MutableStateFlow(0L)
    val grayMatAddr: StateFlow<Long> = _grayMatAddr.asStateFlow()
    private val _edgeMatAddr = MutableStateFlow(0L)
    val edgeMatAddr: StateFlow<Long> = _edgeMatAddr.asStateFlow()

    // Structural LSD line segments (SPEC_06 §2)
    private val _horizontalLines = MutableStateFlow(FloatArray(0))
    val horizontalLines: StateFlow<FloatArray> = _horizontalLines.asStateFlow()

    private val _verticalLines = MutableStateFlow(FloatArray(0))
    val verticalLines: StateFlow<FloatArray> = _verticalLines.asStateFlow()

    private var lsdJob: Job? = null

    fun onBitmapLoaded(bitmap: Bitmap) {
        lsdJob?.cancel()
        lsdJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val detector = com.scanner.app.engine.NativeEdgeDetector()
                val (hLines, vLines) = detector.detectStructuralLines(bitmap)
                _horizontalLines.value = hLines
                _verticalLines.value = vLines
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    private fun updateMats(newGray: Mat?, newEdges: Mat?) {
        val oldGray = grayMat
        val oldEdges = edgeMat
        grayMat = newGray
        edgeMat = newEdges
        _grayMatAddr.value = newGray?.nativeObjAddr ?: 0L
        _edgeMatAddr.value = newEdges?.nativeObjAddr ?: 0L
        oldGray?.release()
        oldEdges?.release()
    }

    private fun computeEdgeMat(imagePath: String) {
        edgeJob?.cancel()
        edgeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val src = Imgcodecs.imread(imagePath)
                if (!src.empty()) {
                    val gray = Mat()
                    Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)

                    // 1. 对比度受限自适应直方图均衡化 (CLAHE) - 提取浅色弱对比度边缘 (SPEC_01 §2.2 & SPEC_05 §3.1)
                    val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                    val claheMat = Mat()
                    clahe.apply(gray, claheMat)

                    // 2. 高斯平滑
                    val blurred = Mat()
                    Imgproc.GaussianBlur(claheMat, blurred, Size(5.0, 5.0), 1.0)

                    // 3. 敏感固定阈值 (25.0, 70.0) - 彻底废弃全局 Otsu 避免弱对比度被吞噬
                    val edges = Mat()
                    Imgproc.Canny(blurred, edges, 25.0, 70.0)

                    // 4. 闭运算连接断裂线
                    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                    Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
                    kernel.release()

                    claheMat.release()
                    blurred.release()
                    src.release()

                    updateMats(gray, edges)
                } else {
                    updateMats(null, null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateMats(null, null)
            }
        }
    }

    fun tapToSnap(touchX: Float, touchY: Float) {
        val addr = _grayMatAddr.value
        if (addr != 0L) {
            viewModelScope.launch(Dispatchers.Default) {
                val detector = com.scanner.app.engine.NativeEdgeDetector()
                val quad = detector.findContourAtPointAddr(addr, touchX, touchY)
                if (quad != null) {
                    _currentQuad.value = quad
                }
            }
        }
    }

    fun loadPage(pageId: String) {
        currentPageId = pageId
        _selectedRatio.value = AspectRatioPreset.FREE
        val page = PageRepository.getPage(pageId) ?: return
        _imagePath.value = page.originalImagePath
        _selectedFilter.value = page.filter
        page.quad?.let {
            _currentQuad.value = it
            _detectedQuad.value = it
        }
        computeEdgeMat(page.originalImagePath)
    }

    fun setAspectRatio(preset: AspectRatioPreset) {
        _selectedRatio.value = preset
        val targetRatio = preset.ratio ?: return

        val path = _imagePath.value ?: return
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOpts)
        val imgW = boundsOpts.outWidth.toFloat()
        val imgH = boundsOpts.outHeight.toFloat()
        if (imgW <= 0f || imgH <= 0f) return

        val current = _currentQuad.value
        val cx = (current.topLeft.x + current.topRight.x + current.bottomRight.x + current.bottomLeft.x) / 4f
        val cy = (current.topLeft.y + current.topRight.y + current.bottomRight.y + current.bottomLeft.y) / 4f

        fun dist(p1: PointF, p2: PointF): Float =
            kotlin.math.hypot(p1.x - p2.x, p1.y - p2.y)

        val currentW = (dist(current.topLeft, current.topRight) + dist(current.bottomLeft, current.bottomRight)) / 2f
        val currentH = (dist(current.topLeft, current.bottomLeft) + dist(current.topRight, current.bottomRight)) / 2f

        val isLandscape = currentW > currentH

        val effectiveRatio = if (isLandscape) {
            if (targetRatio >= 1f) targetRatio else (1f / targetRatio)
        } else {
            if (targetRatio <= 1f) targetRatio else (1f / targetRatio)
        }

        var newW: Float
        var newH: Float

        if (currentW / currentH > effectiveRatio) {
            newH = currentH
            newW = newH * effectiveRatio
        } else {
            newW = currentW
            newH = newW / effectiveRatio
        }

        if (newW > imgW) {
            newW = imgW
            newH = newW / effectiveRatio
        }
        if (newH > imgH) {
            newH = imgH
            newW = newH * effectiveRatio
        }

        val halfW = newW / 2f
        val halfH = newH / 2f

        val clampedCx = cx.coerceIn(halfW, imgW - halfW)
        val clampedCy = cy.coerceIn(halfH, imgH - halfH)

        _currentQuad.value = DocumentQuad(
            topLeft = PointF(clampedCx - halfW, clampedCy - halfH),
            topRight = PointF(clampedCx + halfW, clampedCy - halfH),
            bottomRight = PointF(clampedCx + halfW, clampedCy + halfH),
            bottomLeft = PointF(clampedCx - halfW, clampedCy + halfH)
        )
    }

    fun updateQuad(quad: DocumentQuad) {
        _selectedRatio.value = AspectRatioPreset.FREE
        _currentQuad.value = quad
    }

    fun updateCorner(cornerIndex: Int, newPosition: PointF) {
        _selectedRatio.value = AspectRatioPreset.FREE
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
        _selectedRatio.value = AspectRatioPreset.FREE
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
        _selectedRatio.value = AspectRatioPreset.FREE
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
                        _currentQuad.value = newQuad
                        _imageVersion.value++
                        onComplete()
                    }
                    computeEdgeMat(path)
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
        _selectedRatio.value = AspectRatioPreset.FREE
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
            computeEdgeMat(path)
        }
    }

    override fun onCleared() {
        super.onCleared()
        edgeJob?.cancel()
        updateMats(null, null)
    }

    fun setFilter(filter: ImageFilter) {
        _selectedFilter.value = filter
    }

    fun confirmCrop(onDone: () -> Unit = {}) {
        val id = currentPageId ?: run { onDone(); return }
        val page = PageRepository.getPage(id) ?: run { onDone(); return }
        val rawQuad = _currentQuad.value
        val filter = _selectedFilter.value
        val targetRatio = _selectedRatio.value.ratio ?: 0f

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val srcMat = Imgcodecs.imread(page.originalImagePath)
                if (!srcMat.empty()) {
                    val corrector = com.scanner.app.engine.NativePerspective()
                    val warpedMat = corrector.processDocument(srcMat, rawQuad, filter, targetRatio)

                    val origFile = File(page.originalImagePath)
                    val croppedFile = File(origFile.parentFile, "crop_${page.id}.jpg")
                    Imgcodecs.imwrite(croppedFile.absolutePath, warpedMat)

                    srcMat.release()
                    warpedMat.release()

                    val updatedPage = page.copy(
                        processedImagePath = croppedFile.absolutePath,
                        quad = rawQuad,
                        filter = filter,
                        targetAspectRatio = _selectedRatio.value.ratio
                    )
                    PageRepository.updatePage(updatedPage)
                    withContext(Dispatchers.Main) {
                        onDone()
                    }
                    return@launch
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val updatedPage = page.copy(quad = rawQuad, filter = filter, targetAspectRatio = _selectedRatio.value.ratio)
            PageRepository.updatePage(updatedPage)
            withContext(Dispatchers.Main) {
                onDone()
            }
        }
    }
}
