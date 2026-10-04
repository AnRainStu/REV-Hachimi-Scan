package com.scanner.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scanner.app.data.image.CropFilterPreview
import com.scanner.app.domain.model.ImageFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the native crop preview with colored paper, uneven illumination and dark text.
 * All inputs stay in memory: these checks do not edit the repository or write scan files.
 */
@RunWith(AndroidJUnit4::class)
class CropFilterPreviewTest {
    @Test fun nativePreviewsChangePixelsAndKeepTheDraftIntact() {
        val source = documentBitmap()
        val original = pixels(source)
        try {
            withPreview(source, ImageFilter.ORIGINAL) { preview ->
                assertArrayEquals("Original must keep source colors", original, pixels(preview))
            }
            withPreview(source, ImageFilter.GRAYSCALE) { preview ->
                val gray = pixels(preview)
                assertGrayChannels(gray)
                assertVisibleChange(original, gray, "Grayscale")
                assertTrue("Grayscale should retain tonal variation", gray.map { Color.red(it) }.toSet().size > 32)
            }
            withPreview(source, ImageFilter.BW) { preview ->
                val bw = pixels(preview)
                assertGrayChannels(bw)
                assertVisibleChange(original, bw, "Black and white")
                assertTrue("Paper should be mostly white", bw.count { Color.red(it) == 255 } > bw.size / 2)
                assertTrue("Dark text must survive", bw.count { Color.red(it) < 64 } > 100)
                withPreview(source, ImageFilter.GRAYSCALE) { gray ->
                    assertVisibleChange(pixels(gray), bw, "Black and white versus grayscale")
                }
            }
            withPreview(source, ImageFilter.MAGIC_COLOR) { preview ->
                assertVisibleChange(original, pixels(preview), "Magic")
                assertTrue("Magic should lighten shadowed paper",
                    brightness(preview.getPixel(8, 240)) > brightness(source.getPixel(8, 240)) + 10)
                val redStamp = preview.getPixel(64, 84)
                val blueMark = preview.getPixel(240, 84)
                assertTrue("The red stamp must not swap to blue", Color.red(redStamp) > Color.blue(redStamp))
                assertTrue("The blue mark must not swap to red", Color.blue(blueMark) > Color.red(blueMark))
            }
            assertFalse("Preview must not recycle its input", source.isRecycled)
            assertArrayEquals("Preview must leave the original draft unchanged", original, pixels(source))
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    @Test fun switchingFiltersRepeatedlyAlwaysStartsFromTheSameSource() {
        val source = documentBitmap()
        val original = pixels(source)
        try {
            val expected = ImageFilter.entries.associateWith { filter ->
                var rendered: IntArray? = null
                withPreview(source, filter) { rendered = pixels(it) }
                checkNotNull(rendered)
            }
            val sequence = listOf(ImageFilter.BW, ImageFilter.MAGIC_COLOR, ImageFilter.GRAYSCALE,
                ImageFilter.ORIGINAL, ImageFilter.GRAYSCALE, ImageFilter.BW,
                ImageFilter.MAGIC_COLOR, ImageFilter.ORIGINAL)
            sequence.forEach { filter ->
                withPreview(source, filter) { preview ->
                    assertArrayEquals("$filter must not compound the previous preview", expected.getValue(filter), pixels(preview))
                }
                assertFalse(source.isRecycled)
                assertArrayEquals("Switching $filter must not mutate the source", original, pixels(source))
            }
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    @Test fun aRotatedDraftKeepsItsDimensionsAndCornerGeometry() {
        val source = documentBitmap()
        val original = pixels(source)
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(90f) }, false)
        val rotatedOriginal = pixels(rotated)
        try {
            assertEquals(source.height, rotated.width)
            assertEquals(source.width, rotated.height)
            withPreview(rotated, ImageFilter.BW) { preview ->
                // The 8px marker at x=28..36, y=410..418 moves to x=62..70, y=28..36.
                assertTrue("Rotation must preserve the marker's crop coordinate", Color.red(preview.getPixel(65, 32)) < 64)
                assertTrue("The opposite paper corner must remain light", Color.red(preview.getPixel(450, 280)) > 200)
            }
            assertArrayEquals(rotatedOriginal, pixels(rotated))
            assertArrayEquals(original, pixels(source))
        } finally {
            if (rotated !== source && !rotated.isRecycled) rotated.recycle()
            if (!source.isRecycled) source.recycle()
        }
    }

    private inline fun withPreview(source: Bitmap, filter: ImageFilter, inspect: (Bitmap) -> Unit) {
        val preview = CropFilterPreview.render(source, filter)
        try {
            assertNotSame("Preview results must own separate pixels", source, preview)
            assertEquals("Filtering must preserve crop width", source.width, preview.width)
            assertEquals("Filtering must preserve crop height", source.height, preview.height)
            assertEquals(Bitmap.Config.ARGB_8888, preview.config)
            inspect(preview)
        } finally {
            if (preview !== source && !preview.isRecycled) preview.recycle()
        }
    }

    private fun assertGrayChannels(values: IntArray) {
        assertTrue("Filtered preview must have equal RGB channels", values.all {
            Color.red(it) == Color.green(it) && Color.green(it) == Color.blue(it) && Color.alpha(it) == 255
        })
    }

    private fun assertVisibleChange(before: IntArray, after: IntArray, label: String) {
        assertEquals(before.size, after.size)
        val changed = before.indices.count { before[it] != after[it] }
        assertTrue("$label must visibly change this colored document ($changed pixels)", changed > before.size / 20)
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun brightness(color: Int) = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3

    private fun documentBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 320f, 0f,
                Color.rgb(164, 155, 139), Color.rgb(248, 244, 230), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 320f, 480f, paint)
        paint.shader = null
        paint.color = Color.rgb(185, 36, 54)
        canvas.drawRect(28f, 58f, 110f, 118f, paint)
        paint.color = Color.rgb(28, 91, 168)
        canvas.drawRect(190f, 58f, 290f, 118f, paint)
        paint.color = Color.rgb(15, 18, 22)
        paint.textSize = 22f
        canvas.drawText("PREVIEW TEST", 28f, 182f, paint)
        paint.textSize = 17f
        canvas.drawText("Colored ink / shaded paper", 28f, 218f, paint)
        repeat(5) { row ->
            val top = 250f + row * 25f
            canvas.drawRect(28f, top, if (row % 2 == 0) 280f else 225f, top + 4f, paint)
        }
        paint.color = Color.rgb(10, 12, 15)
        canvas.drawRect(28f, 410f, 36f, 418f, paint)
        return bitmap
    }
}
