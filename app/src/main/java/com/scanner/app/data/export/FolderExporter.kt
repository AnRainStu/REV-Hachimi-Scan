package com.scanner.app.data.export

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.scanner.app.domain.model.ExportConfig
import com.scanner.app.domain.model.ScannedPage
import java.io.File
import java.util.Locale

class FolderExporter(private val context: Context) {
    fun export(pages: List<ScannedPage>, config: ExportConfig): ExportResult {
        require(pages.isNotEmpty()) { "Select at least one page" }
        val name = config.validatedName()
        val directory = "${Environment.DIRECTORY_PICTURES}/HachiCam/$name"
        val writer = MediaStoreWriter(context.contentResolver)
        try {
            val uris = pages.mapIndexed { index, page ->
                val source = File(page.imagePath)
                check(source.isFile && source.length() > 0) { "Cannot read page ${index + 1}" }
                writer.write(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, directory,
                    String.format(Locale.US, "%s-P%03d.jpg", name, index + 1), "image/jpeg") { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
            }
            writer.publish()
            return ExportResult(uris, directory, "image/jpeg")
        } catch (e: Throwable) { writer.rollback(); throw e }
    }
}
