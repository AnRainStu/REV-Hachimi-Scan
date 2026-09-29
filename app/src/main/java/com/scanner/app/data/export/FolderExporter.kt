package com.scanner.app.data.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.scanner.app.domain.model.ExportConfig
import com.scanner.app.domain.model.ScannedPage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class FolderExporter(private val context: Context) {

    fun export(pages: List<ScannedPage>, config: ExportConfig): File {
        val folderName = config.name
        val resolver = context.contentResolver

        val baseName = if (folderName.isNotBlank()) {
            folderName
        } else {
            java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.getDefault()).format(java.util.Date())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = Environment.DIRECTORY_PICTURES + File.separator + "DocScanner" + File.separator + baseName
            
            pages.forEachIndexed { index, page ->
                val fileName = String.format(java.util.Locale.US, "%s-P%03d.jpg", baseName, index + 1)
                
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                
                val uri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { outStream ->
                        FileInputStream(File(page.imagePath)).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                }
            }
            
            return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocScanner/$baseName")
        } else {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val exportDir = File(picturesDir, "DocScanner/$baseName")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            
            pages.forEachIndexed { index, page ->
                val fileName = String.format(java.util.Locale.US, "%s-P%03d.jpg", baseName, index + 1)
                val destFile = File(exportDir, fileName)
                
                FileInputStream(File(page.imagePath)).use { inStream ->
                    FileOutputStream(destFile).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
            }
            return exportDir
        }
    }
}
