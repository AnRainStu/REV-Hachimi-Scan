package com.scanner.app.data.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import com.scanner.app.domain.model.ExportConfig
import com.scanner.app.domain.model.ScannedPage

class PdfExporter(private val context: Context) {
    fun export(pages: List<ScannedPage>, config: ExportConfig): ExportResult {
        require(pages.isNotEmpty()) { "Select at least one page" }
        val writer = MediaStoreWriter(context.contentResolver)
        val document = PdfDocument()
        val directory = "${Environment.DIRECTORY_DOWNLOADS}/HachiCam"
        try {
            pages.forEachIndexed { index, page ->
                val bitmap = checkNotNull(BitmapFactory.decodeFile(page.imagePath)) { "Cannot read page ${index + 1}" }
                try {
                    // PDF units are points, not camera pixels. Keep each page's aspect ratio.
                    val width = 595
                    val height = (width.toDouble() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
                    val pdfPage = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                    pdfPage.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
                    document.finishPage(pdfPage)
                } finally { bitmap.recycle() }
            }
            val uri = writer.write(MediaStore.Downloads.EXTERNAL_CONTENT_URI, directory,
                "${config.validatedName()}.pdf", "application/pdf") { document.writeTo(it) }
            val actualName = writer.displayName(uri)
            writer.publish()
            return ExportResult(listOf(uri), "$directory/$actualName", "application/pdf")
        } catch (e: Throwable) { writer.rollback(); throw e }
        finally { document.close() }
    }
}
