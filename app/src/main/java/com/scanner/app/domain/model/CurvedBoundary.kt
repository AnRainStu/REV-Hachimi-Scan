package com.scanner.app.domain.model

import android.graphics.PointF

data class CurvedBoundary(
    val topCurve: List<PointF>,
    val bottomCurve: List<PointF>,
    val leftCurve: List<PointF>,
    val rightCurve: List<PointF>
)
