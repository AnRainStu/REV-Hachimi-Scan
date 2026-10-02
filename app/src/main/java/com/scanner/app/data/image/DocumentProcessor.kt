package com.scanner.app.data.image

import android.graphics.PointF
import com.scanner.app.data.util.ExifUtils
import com.scanner.app.domain.model.DocumentQuad
import com.scanner.app.domain.model.ScannedPage
import com.scanner.app.engine.NativePerspective
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.UUID

/** Every edit renders from the original into a new file, preserving the last successful edit. */
class DocumentProcessor {
    fun process(page: ScannedPage): ScannedPage {
        val source = Imgcodecs.imread(page.originalImagePath)
        var output: Mat? = null
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95, Imgcodecs.IMWRITE_JPEG_OPTIMIZE, 1)
        val destination = File(File(page.originalImagePath).parentFile, "edit_${UUID.randomUUID()}.jpg")
        try {
            check(!source.empty()) { "Cannot read the original image" }
            when ((page.sourceRotation % 360 + 360) % 360) {
                90 -> Core.rotate(source, source, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(source, source, Core.ROTATE_180)
                270 -> Core.rotate(source, source, Core.ROTATE_90_COUNTERCLOCKWISE)
            }
            val quad = page.quad ?: DocumentQuad(
                PointF(0f, 0f), PointF(source.cols().toFloat(), 0f),
                PointF(source.cols().toFloat(), source.rows().toFloat()), PointF(0f, source.rows().toFloat())
            )
            require(quad.toPointList().all { it.x in 0f..source.cols().toFloat() && it.y in 0f..source.rows().toFloat() }) {
                "Crop corners are outside the image"
            }
            require(quad.isValid()) { "Crop corners must form a convex document" }
            val ratio = page.targetAspectRatio ?: 0f
            require(ratio == 0f || (ratio.isFinite() && ratio >= 1f / 12f && ratio <= 12f)) { "Invalid aspect ratio" }
            output = NativePerspective().processDocument(source, quad, page.filter, ratio)
            check(!output.empty()) { "Document processing returned an empty image" }
            val rotation = (page.rotation % 360 + 360) % 360
            require(rotation % 90 == 0) { "Rotation must be a multiple of 90" }
            if (rotation != 0) {
                val rotated = Mat()
                try {
                    Core.rotate(output, rotated, when (rotation) {
                        90 -> Core.ROTATE_90_CLOCKWISE
                        180 -> Core.ROTATE_180
                        else -> Core.ROTATE_90_COUNTERCLOCKWISE
                    })
                } catch (e: Exception) { rotated.release(); throw e }
                output.release(); output = rotated
            }
            check(Imgcodecs.imwrite(destination.absolutePath, output, params)) { "Cannot save the processed image" }
            ExifUtils.copyAndStampExif(File(page.originalImagePath), destination)
            return page.copy(processedImagePath = destination.absolutePath, thumbnailPath = null)
        } catch (e: Throwable) { destination.delete(); throw e }
        finally { source.release(); output?.release(); params.release() }
    }
}
