package com.scanner.app.domain.model

import android.graphics.PointF

data class DocumentQuad(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        topLeft.x, topLeft.y,
        topRight.x, topRight.y,
        bottomRight.x, bottomRight.y,
        bottomLeft.x, bottomLeft.y
    )
    
    fun toPointList(): List<PointF> = listOf(topLeft, topRight, bottomRight, bottomLeft)
    
    companion object {
        fun fromFloatArray(arr: FloatArray): DocumentQuad {
            require(arr.size >= 8)
            return DocumentQuad(
                topLeft = PointF(arr[0], arr[1]),
                topRight = PointF(arr[2], arr[3]),
                bottomRight = PointF(arr[4], arr[5]),
                bottomLeft = PointF(arr[6], arr[7])
            )
        }
    }
}
