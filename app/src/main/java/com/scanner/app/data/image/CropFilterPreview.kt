package com.scanner.app.data.image

import android.graphics.Bitmap
import com.scanner.app.domain.model.ImageFilter
import com.scanner.app.engine.NativePerspective
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Applies the saved document's native filter to an unmodified, bounded crop source. */
object CropFilterPreview {
    /** The caller owns the new ARGB bitmap; [source] is never changed or recycled. */
    fun render(source: Bitmap, filter: ImageFilter): Bitmap {
        require(!source.isRecycled) { "The preview source is no longer available" }
        val rgba = Mat()
        val bgr = Mat()
        val resultRgba = Mat()
        var filtered: Mat? = null
        var bitmap: Bitmap? = null
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            val result = NativePerspective().applyFilter(bgr, filter)
            filtered = result
            check(!result.empty()) { "The filter returned an empty preview" }
            when (result.channels()) {
                1 -> Imgproc.cvtColor(result, resultRgba, Imgproc.COLOR_GRAY2RGBA)
                3 -> Imgproc.cvtColor(result, resultRgba, Imgproc.COLOR_BGR2RGBA)
                4 -> Imgproc.cvtColor(result, resultRgba, Imgproc.COLOR_BGRA2RGBA)
                else -> error("Unsupported preview image format")
            }
            val output = Bitmap.createBitmap(resultRgba.cols(), resultRgba.rows(), Bitmap.Config.ARGB_8888)
            bitmap = output
            Utils.matToBitmap(resultRgba, output)
            return output
        } catch (error: Throwable) {
            bitmap?.recycle()
            throw error
        } finally {
            rgba.release()
            bgr.release()
            filtered?.release()
            resultRgba.release()
        }
    }
}
