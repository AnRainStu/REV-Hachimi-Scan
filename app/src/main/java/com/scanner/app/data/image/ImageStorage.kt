package com.scanner.app.data.image

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImageStorage(private val context: Context) {

    fun saveScannedImage(bitmap: Bitmap, quality: Int = 90): String {
        val file = File(getStorageDir(), "${generateFileName()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return file.absolutePath
    }

    fun saveThumbnail(bitmap: Bitmap): String {
        val file = File(getStorageDir(), "thumb_${generateFileName()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
        }
        return file.absolutePath
    }

    fun deleteImage(path: String) {
        val file = File(path)
        if (file.exists()) {
            file.delete()
        }
    }

    fun getStorageDir(): File {
        val dir = File(context.filesDir, "scans")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun generateFileName(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    }
}
