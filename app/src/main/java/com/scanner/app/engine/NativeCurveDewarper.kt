package com.scanner.app.engine

import com.scanner.app.domain.model.CurvedBoundary
import org.opencv.core.Mat
import android.graphics.PointF

class NativeCurveDewarper {

    init {
        System.loadLibrary("doc_scanner_engine")
    }

    fun dewarpCurved(srcMat: Mat, boundary: CurvedBoundary): Mat {
        val topArr = boundary.topCurve.toFloatArray()
        val bottomArr = boundary.bottomCurve.toFloatArray()
        val leftArr = boundary.leftCurve.toFloatArray()
        val rightArr = boundary.rightCurve.toFloatArray()
        
        val resultMatAddr = nativeDewarpCurved(srcMat.nativeObjAddr, topArr, bottomArr, leftArr, rightArr)
        check(resultMatAddr != 0L) { "Native processing failed" }
        return Mat(resultMatAddr)
    }

    private fun List<PointF>.toFloatArray(): FloatArray {
        val arr = FloatArray(this.size * 2)
        for (i in indices) {
            arr[i * 2] = this[i].x
            arr[i * 2 + 1] = this[i].y
        }
        return arr
    }

    private external fun nativeDewarpCurved(
        srcMatAddr: Long,
        topPts: FloatArray,
        bottomPts: FloatArray,
        leftPts: FloatArray,
        rightPts: FloatArray
    ): Long
}
