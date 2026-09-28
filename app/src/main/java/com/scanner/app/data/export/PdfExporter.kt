package com.scanner.app.data.export

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.scanner.app.domain.model.ExportConfig
import com.scanner.app.domain.model.ScannedPage
import java.io.File
import java.io.FileOutputStream

class PdfExporter(private val context: Context) {

    fun export(pages: List<ScannedPage>, config: ExportConfig): File {
        val document = PdfDocument()
        
        try {
            var pageNum = 1
            pages.forEach { page ->
                val imageFile = File(page.imagePath)
                if (imageFile.exists()) {
                    val bmp = BitmapFactory.decodeFile(page.imagePath)
                    if (bmp != null) {
                        val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, pageNum++).create()
                        val pdfPage = document.startPage(pageInfo)
                        pdfPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                        document.finishPage(pdfPage)
                        bmp.recycle()
                    }
                }
            }

            val fileName = "${config.name}.pdf"
            val resolver = context.contentResolver
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = Environment.DIRECTORY_DOCUMENTS + File.separator + "DocScanner"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outStream ->
                        document.writeTo(outStream)
                    }
                    return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DocScanner/$fileName")
                }
            }
            
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val exportDir = File(docsDir, "DocScanner")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            val destFile = File(exportDir, fileName)
            FileOutputStream(destFile).use { outStream ->
                document.writeTo(outStream)
            }
            return destFile
            
        } finally {
            document.close()
        }
    }
}
