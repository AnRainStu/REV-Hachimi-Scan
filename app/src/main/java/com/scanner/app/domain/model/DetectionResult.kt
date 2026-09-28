package com.scanner.app.domain.model

import android.graphics.PointF

data class DetectionResult(
    val found: Boolean,
    val quad: DocumentQuad? = null,
    val boundaryPoints: List<PointF>? = null,
    val isCurved: Boolean = false,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val isStable: Boolean = false
) {
    val points: List<PointF>
        get() = quad?.toPointList() ?: boundaryPoints ?: emptyList()
}
