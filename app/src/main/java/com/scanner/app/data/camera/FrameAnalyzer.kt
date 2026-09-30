package com.scanner.app.data.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.scanner.app.domain.model.DetectionResult
import com.scanner.app.domain.model.DocumentQuad
import com.scanner.app.engine.NativeEdgeDetector
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Core
import kotlin.math.hypot
import kotlin.math.max

class FrameAnalyzer(
    private val detector: NativeEdgeDetector,
    var curvedMode: Boolean = false,
    private val onResult: (DetectionResult) -> Unit
) : ImageAnalysis.Analyzer {

    private data class FrameQuadRecord(
        val quad: DocumentQuad,
        val timestampMs: Long
    )

    private val recentQuads = ArrayDeque<FrameQuadRecord>(5)
    private var stableSinceMs: Long = 0L

    var touchPoint: android.graphics.PointF? = null

    fun resetStability() {
        recentQuads.clear()
        stableSinceMs = 0L
    }

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val yPlane = imageProxy.planes[0]
            val yBuffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val width = imageProxy.width
            val height = imageProxy.height

            val grayMat = Mat(height, width, CvType.CV_8UC1)
            if (rowStride == width && pixelStride == 1) {
                val yBytes = ByteArray(width * height)
                yBuffer.get(yBytes)
                grayMat.put(0, 0, yBytes)
            } else {
                val rowData = ByteArray(width)
                for (row in 0 until height) {
                    yBuffer.position(row * rowStride)
                    yBuffer.get(rowData, 0, width)
                    grayMat.put(row, 0, rowData)
                }
            }
            
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val rotatedMat = Mat()
            
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                Core.transpose(grayMat, rotatedMat)
                Core.flip(rotatedMat, rotatedMat, if (rotationDegrees == 90) 1 else 0)
            } else if (rotationDegrees == 180) {
                Core.flip(grayMat, rotatedMat, -1)
            } else {
                grayMat.copyTo(rotatedMat)
            }

            val tp = touchPoint
            val touchX = tp?.x ?: -1.0f
            val touchY = tp?.y ?: -1.0f
            val result = detector.detectDocument(rotatedMat, curvedMode, touchX, touchY)
            val now = System.currentTimeMillis()
            val frameW = rotatedMat.cols()
            val frameH = rotatedMat.rows()
            val diagonal = hypot(frameW.toDouble(), frameH.toDouble()).toFloat()
            val stabilityThreshold = 0.005f * diagonal

            var isStable = false
            if (result.found && result.quad != null) {
                recentQuads.addLast(FrameQuadRecord(result.quad, now))
                while (recentQuads.size > 5) {
                    recentQuads.removeFirst()
                }

                if (recentQuads.size >= 2) {
                    var maxDrift = 0f
                    for (i in 1 until recentQuads.size) {
                        val q1 = recentQuads[i - 1].quad
                        val q2 = recentQuads[i].quad
                        val d0 = hypot((q2.topLeft.x - q1.topLeft.x).toDouble(), (q2.topLeft.y - q1.topLeft.y).toDouble()).toFloat()
                        val d1 = hypot((q2.topRight.x - q1.topRight.x).toDouble(), (q2.topRight.y - q1.topRight.y).toDouble()).toFloat()
                        val d2 = hypot((q2.bottomRight.x - q1.bottomRight.x).toDouble(), (q2.bottomRight.y - q1.bottomRight.y).toDouble()).toFloat()
                        val d3 = hypot((q2.bottomLeft.x - q1.bottomLeft.x).toDouble(), (q2.bottomLeft.y - q1.bottomLeft.y).toDouble()).toFloat()
                        val drift = max(max(d0, d1), max(d2, d3))
                        if (drift > maxDrift) {
                            maxDrift = drift
                        }
                    }

                    if (maxDrift < stabilityThreshold) {
                        if (stableSinceMs == 0L) {
                            stableSinceMs = now
                        }
                        if (now - stableSinceMs >= 300L) {
                            isStable = true
                        }
                    } else {
                        stableSinceMs = 0L
                        isStable = false
                    }
                } else {
                    stableSinceMs = 0L
                    isStable = false
                }
            } else {
                recentQuads.clear()
                stableSinceMs = 0L
                isStable = false
            }

            val finalResult = result.copy(isStable = isStable)
            onResult(finalResult)

            grayMat.release()
            rotatedMat.release()
        } finally {
            imageProxy.close()
        }
    }
}
