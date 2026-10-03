package com.scanner.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.BLACK; textSize = 28f }
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
            assertTrue(File(rendered.imagePath).delete())
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
        for (uri in pdf.uris + images.uris) { assertEquals(1, context.contentResolver.delete(uri, null, null)) }
        PageRepository.clear()
        assertFalse(source.exists())
        assertFalse(File(rotatedSource.imagePath).exists())

        // Keep useful portrait samples for actual UI inspection after restarting the app.
        // Fixtures are generated locally: no real personal data, fonts, photos, or network requests.
        val fixtureKeys = listOf("invoice", "coast", "notes", "receipt", "pass", "letter")
        val originals = mutableMapOf<File, ByteArray>()
        fixtureKeys.forEachIndexed { index, key ->
            val fixture = File(context.filesDir, "scans/fixture-$key-original.jpg")
            createFixture(key).also { sample ->
                fixture.outputStream().use { assertTrue(sample.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                sample.recycle()
            }
            originals[fixture] = fixture.readBytes()
            val sample = processor.process(ScannedPage(
                id = "fixture-$key", originalImagePath = fixture.absolutePath,
                filter = ImageFilter.ORIGINAL,
                createdAt = System.currentTimeMillis() - index * 24 * 60 * 60 * 1000L
            ))
            val rendered = android.graphics.BitmapFactory.decodeFile(sample.imagePath)
            assertNotNull(rendered)
            assertTrue("Fixture must be portrait: $key", rendered.height > rendered.width)
            rendered.recycle()
            assertArrayEquals(originals.getValue(fixture), fixture.readBytes())
            PageRepository.addPage(sample)
        }
        val saved = PageRepository.pages.value
        PageRepository.initialize(context)
        assertEquals(saved, PageRepository.pages.value)
        assertEquals(6, PageRepository.pages.value.size)
        originals.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        val referenced = saved.flatMap { listOfNotNull(it.originalImagePath, it.processedImagePath, it.thumbnailPath) }.toSet()
        assertEquals("Fixture setup left unreferenced images", referenced,
            File(context.filesDir, "scans").listFiles()!!.filter { it.extension == "jpg" }.map { it.absolutePath }.toSet())
    }

    private fun createFixture(kind: String): Bitmap {
        val bitmap = Bitmap.createBitmap(900, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val ink = Color.rgb(34, 41, 49)
        val muted = Color.rgb(112, 122, 135)
        val blue = Color.rgb(40, 82, 150)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(value: String, x: Float, y: Float, size: Float = 28f, color: Int = ink, bold: Boolean = false) {
            paint.apply { this.color = color; textSize = size; shader = null; style = Paint.Style.FILL
                typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL) }
            canvas.drawText(value, x, y, paint)
        }
        fun block(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float = 0f) {
            paint.apply { this.color = color; shader = null; style = Paint.Style.FILL }
            canvas.drawRoundRect(RectF(x, y, x + w, y + h), radius, radius, paint)
        }
        fun line(y: Float, color: Int = Color.rgb(220, 225, 230)) = block(70f, y, 760f, 2f, color)
        fun footer() {
            line(1172f)
            text("SYNTHETIC SAMPLE / UI TEST FIXTURE", 70f, 1222f, 18f, muted)
        }
        bitmap.eraseColor(Color.rgb(255, 254, 250))
        when (kind) {
            "invoice" -> {
                block(0f, 0f, 900f, 22f, blue)
                text("STUDIO / 04", 70f, 112f, 29f, blue, true)
                text("Invoice", 70f, 244f, 76f, ink, true)
                text("INV-2026-042", 70f, 294f, 23f, muted)
                text("BILL TO", 70f, 406f, 18f, muted, true)
                text("Example Design Co.", 70f, 452f, 32f, ink, true)
                text("42 Sample Street", 70f, 494f, 27f, muted)
                text("ISSUED", 574f, 406f, 18f, muted, true)
                text("02 OCT 2026", 574f, 452f, 27f)
                block(70f, 578f, 760f, 64f, Color.rgb(235, 240, 248), 8f)
                text("DESCRIPTION", 92f, 620f, 19f, blue, true)
                text("AMOUNT", 669f, 620f, 19f, blue, true)
                listOf("Research and discovery" to "$480.00", "Visual design" to "$620.00", "Prototype review" to "$180.00").forEachIndexed { i, row ->
                    val y = 709f + i * 86f
                    text(row.first, 91f, y, 27f); text(row.second, 683f, y, 25f); line(y + 30f)
                }
                text("TOTAL DUE", 70f, 1040f, 22f, muted, true)
                text("$1,280.00", 497f, 1040f, 55f, blue, true)
                footer()
            }
            "coast" -> {
                text("FIELD JOURNAL", 70f, 108f, 20f, muted, true)
                text("Coastal study", 70f, 201f, 64f, ink, true)
                text("Shapes, light, and the afternoon sea.", 70f, 260f, 27f, muted)
                paint.shader = LinearGradient(70f, 320f, 830f, 882f,
                    intArrayOf(Color.rgb(89, 152, 194), Color.rgb(178, 213, 207), Color.rgb(225, 210, 163)),
                    floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(70f, 320f, 830f, 886f, paint)
                paint.shader = null
                paint.color = Color.rgb(244, 222, 163)
                canvas.drawCircle(701f, 425f, 51f, paint)
                paint.color = Color.rgb(47, 103, 129)
                canvas.drawPath(Path().apply { moveTo(70f, 785f); lineTo(238f, 553f); lineTo(381f, 749f)
                    lineTo(475f, 625f); lineTo(705f, 886f); lineTo(70f, 886f); close() }, paint)
                paint.color = Color.rgb(76, 132, 135)
                canvas.drawPath(Path().apply { moveTo(410f, 886f); lineTo(653f, 694f); lineTo(830f, 774f)
                    lineTo(830f, 886f); close() }, paint)
                text("01 / LOOK CLOSER", 70f, 956f, 21f, blue, true)
                text("A simple composition drawn for this demo.", 70f, 1006f, 27f)
                text("Nothing to rush. A little more room to see.", 70f, 1050f, 27f, muted)
                footer()
            }
            "notes" -> {
                block(0f, 0f, 900f, 1280f, Color.rgb(252, 249, 233))
                text("FIELD NOTES", 70f, 109f, 22f, Color.rgb(145, 114, 58), true)
                text("A quieter workspace", 70f, 206f, 59f, ink, true)
                text("Ideas for the next iteration", 70f, 263f, 28f, muted)
                listOf("01  Give the document room to breathe.", "02  Keep everyday actions within reach.",
                    "03  Make the important details obvious.", "04  Let light reveal the material.", "05  Leave a little room for curiosity.").forEachIndexed { i, value ->
                    val y = 406f + i * 119f
                    text(value, 70f, y, 28f); line(y + 35f, Color.rgb(225, 219, 197))
                }
                block(70f, 1020f, 760f, 83f, Color.rgb(244, 235, 193), 12f)
                text("Small details change how a tool feels.", 100f, 1074f, 29f, Color.rgb(114, 89, 40))
                footer()
            }
            "receipt" -> {
                text("MONDAY", 211f, 137f, 75f, ink, true)
                text("COFFEE & COMPANY", 241f, 192f, 27f, muted)
                text("DEMO RECEIPT", 304f, 298f, 24f, blue, true)
                text("02 OCT 2026   /   09:41", 265f, 348f, 26f, muted)
                line(414f)
                listOf("Flat white" to "4.50", "Almond croissant" to "3.80", "Filter coffee" to "3.00").forEachIndexed { i, row ->
                    text(row.first, 90f, 498f + i * 92f, 33f); text(row.second, 717f, 498f + i * 92f, 33f)
                }
                line(760f)
                text("TOTAL", 90f, 854f, 34f, ink, true)
                text("$11.30", 637f, 854f, 48f, blue, true)
                text("Paid with sample card **** 0000", 169f, 948f, 27f, muted)
                text("THANK YOU / SEE YOU SOON", 189f, 1060f, 27f, ink, true)
                footer()
            }
            "pass" -> {
                block(0f, 0f, 900f, 382f, Color.rgb(181, 71, 53))
                text("ARCHIVE", 70f, 143f, 83f, Color.WHITE, true)
                text("MUSEUM", 70f, 241f, 83f, Color.WHITE, true)
                text("ART / FORM / EVERYDAY LIFE", 75f, 318f, 24f, Color.rgb(249, 213, 203))
                text("Day pass", 70f, 512f, 65f, ink, true)
                text("ADMIT ONE / SAMPLE VISITOR", 70f, 566f, 24f, muted)
                text("DATE", 70f, 678f, 20f, muted, true)
                text("02 OCT 2026", 70f, 727f, 31f, ink, true)
                text("ENTRY", 570f, 678f, 20f, muted, true)
                text("10:00 - 18:00", 570f, 727f, 29f)
                // Deliberately decorative grid, not a valid ticket or QR code.
                repeat(18) { row -> repeat(18) { col ->
                    if ((row * 11 + col * 7 + row * col) % 5 < 2) block(72f + col * 12f, 809f + row * 12f, 10f, 10f, ink)
                } }
                text("DEMO ONLY", 352f, 883f, 39f, Color.rgb(181, 71, 53), true)
                text("Not valid for entry.", 353f, 936f, 29f, muted)
                text("A sample ticket for testing document scans.", 70f, 1098f, 26f, muted)
                footer()
            }
            else -> {
                block(70f, 82f, 12f, 87f, blue, 6f)
                text("NORTH / OFFICE", 103f, 127f, 31f, blue, true)
                text("SAMPLE CORRESPONDENCE", 103f, 164f, 17f, muted)
                text("02 October 2026", 70f, 299f, 26f, muted)
                text("Dear reader,", 70f, 410f, 35f, ink, true)
                listOf("Good tools make everyday work feel lighter.", "They give useful things a place to live,", "keep the next step clear, and make room", "for the details that deserve attention.", "", "This letter is a synthetic document created", "to inspect typography, cropping, and light.", "All names and information are examples.").forEachIndexed { i, value ->
                    text(value, 70f, 488f + i * 53f, 30f)
                }
                text("With care,", 70f, 1050f, 30f)
                text("The Sample Studio", 70f, 1100f, 34f, blue, true)
                footer()
            }
        }
        return bitmap
    }
}
