package com.scanner.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scanner.app.data.export.FolderExporter
import com.scanner.app.data.export.PdfExporter
import com.scanner.app.data.image.DocumentProcessor
import com.scanner.app.data.repository.PageRepository
import com.scanner.app.domain.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativePipelineTest {
    @Test fun filtersRotationPersistenceAndRealExports() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "scans/integration-original.jpg")
        source.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply { color = Color.BLACK; textSize = 28f }
        canvas.drawText("Hachimi Scan", 24f, 70f, paint)
        canvas.drawText("Invoice 2026", 24f, 120f, paint)
        source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        val original = source.readBytes()
        val page = ScannedPage(originalImagePath = source.absolutePath,
            quad = DocumentQuad(PointF(0f, 0f), PointF(320f, 0f), PointF(320f, 480f), PointF(0f, 480f)))
        val processor = DocumentProcessor()
        for (filter in ImageFilter.entries) {
            val rendered = processor.process(page.copy(filter = filter, rotation = 90))
            val image = android.graphics.BitmapFactory.decodeFile(rendered.imagePath)
            assertNotNull(image); assertTrue(image.width > image.height); image.recycle()
            assertArrayEquals(original, source.readBytes())
            File(rendered.imagePath).delete()
        }
        val rotatedSource = processor.process(page.copy(sourceRotation = 90,
            quad = DocumentQuad(PointF(0f, 0f), PointF(480f, 0f), PointF(480f, 320f), PointF(0f, 320f))))
        assertArrayEquals(original, source.readBytes())
        PageRepository.addPage(rotatedSource)
        PageRepository.initialize(context)
        assertEquals(rotatedSource, PageRepository.getPage(rotatedSource.id))
        val pdf = PdfExporter(context).export(listOf(rotatedSource), ExportConfig(name = "integration"))
        context.contentResolver.openInputStream(pdf.uris.first())!!.use {
            val header = ByteArray(4); assertEquals(4, it.read(header)); assertEquals("%PDF", String(header))
        }
        val images = FolderExporter(context).export(listOf(rotatedSource), ExportConfig(name = "integration"))
        for (uri in pdf.uris + images.uris) { context.contentResolver.delete(uri, null, null) }
        // Retain one page to inspect restoration and editor UI after the app is restarted.
    }
}
