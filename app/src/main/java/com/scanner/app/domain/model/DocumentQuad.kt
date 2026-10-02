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
    
    fun isValid(): Boolean {
        val p = toPointList()
        if (p.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val cross = p.indices.map { i ->
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return cross.all { it > 1f } || cross.all { it < -1f }
    }

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
