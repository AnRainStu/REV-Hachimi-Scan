package com.scanner.app.data.util

import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.scanner.app.BuildConfig
import java.io.File
import java.io.FileDescriptor

object ExifUtils {

    private val CAMERA_TAGS = arrayOf(
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_ORIENTATION
    )

    fun stampSignature(dstExif: ExifInterface) {
        try {
            dstExif.setAttribute(ExifInterface.TAG_SOFTWARE, "Hachimi Scan v0.1.1 (${BuildConfig.GIT_HASH})")
            dstExif.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, BuildConfig.GIT_HASH)
            dstExif.setAttribute(
                ExifInterface.TAG_USER_COMMENT,
                "Hachimi Scan v0.1.1 (Build: ${BuildConfig.GIT_HASH}, ${BuildConfig.BUILD_TIME})"
            )
            if (BuildConfig.DEBUG) {
                if (dstExif.getAttribute(ExifInterface.TAG_MAKE).isNullOrBlank()) {
                    dstExif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                }
                if (dstExif.getAttribute(ExifInterface.TAG_MODEL).isNullOrBlank()) {
                    dstExif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                }
            } else {
                dstExif.setAttribute(ExifInterface.TAG_MAKE, null)
                dstExif.setAttribute(ExifInterface.TAG_MODEL, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stampSignature(file: File) {
        if (!file.exists()) return
        try {
            val exif = ExifInterface(file.absolutePath)
            stampSignature(exif)
            exif.saveAttributes()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun copyAndStampExif(srcFile: File, dstExif: ExifInterface) {
        if (!srcFile.exists()) return
        try {
            val srcExif = ExifInterface(srcFile.absolutePath)
            for (tag in CAMERA_TAGS) {
                val value = srcExif.getAttribute(tag)
                if (value != null) {
                    dstExif.setAttribute(tag, value)
                }
            }
            dstExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            stampSignature(dstExif)
            dstExif.saveAttributes()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun copyAndStampExif(srcFile: File, dstFile: File) {
        if (!srcFile.exists() || !dstFile.exists()) return
        try {
            val dstExif = ExifInterface(dstFile.absolutePath)
            copyAndStampExif(srcFile, dstExif)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun copyAndStampExif(srcFile: File, dstFd: FileDescriptor) {
        if (!srcFile.exists()) return
        try {
            val dstExif = ExifInterface(dstFd)
            copyAndStampExif(srcFile, dstExif)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
