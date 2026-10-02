package com.scanner.app

import android.app.Application
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import com.scanner.app.data.repository.PageRepository
import com.scanner.app.domain.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageRepositoryTest {
    private lateinit var app: Application
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("scans.db")
        PageRepository.initialize(app)
    }
    private fun page(id: String) = ScannedPage(id = id, originalImagePath = File(app.filesDir, "scans/$id.jpg").apply {
        parentFile!!.mkdirs(); writeText("image-$id")
    }.absolutePath)
    @Test fun restoresEditsAndOrderAfterRepositoryReopens() {
        val original = page("a").copy(quad = DocumentQuad(PointF(0f, 0f), PointF(80f, 0f), PointF(80f, 100f), PointF(0f, 100f)),
            sourceRotation = 90, rotation = 270, filter = ImageFilter.BW, targetAspectRatio = .8f)
        PageRepository.addPage(original); PageRepository.addPage(page("b")); PageRepository.addPage(page("c"))
        PageRepository.reorderPages(0, 2)
        PageRepository.initialize(app)
        assertEquals(listOf("b", "c", "a"), PageRepository.pages.value.map { it.id })
        assertEquals(original, PageRepository.getPage("a"))
    }
    @Test fun deletesOwnedFilesButRetainsSharedOriginal() {
        val a = page("a")
        val edit = File(app.filesDir, "scans/edited.jpg").apply { writeText("edited") }
        PageRepository.addPage(a.copy(processedImagePath = edit.absolutePath))
        PageRepository.addPage(a.copy(id = "b"))
        PageRepository.deletePage("a")
        assertFalse(edit.exists()); assertTrue(File(a.originalImagePath).exists())
        PageRepository.deletePage("b")
        assertFalse(File(a.originalImagePath).exists())
        PageRepository.initialize(app); assertTrue(PageRepository.pages.value.isEmpty())
    }
    @Test fun neverDeletesFilesOutsideManagedDirectory() {
        val outside = File(app.cacheDir, "user-owned.jpg").apply { writeText("original") }
        PageRepository.addPage(ScannedPage(originalImagePath = outside.absolutePath))
        PageRepository.clear()
        assertTrue(outside.exists())
    }
    @Test fun replacingEditKeepsOriginalAndRemovesSupersededOutput() {
        val a = page("a")
        val old = File(app.filesDir, "scans/old.jpg").apply { writeText("old") }
        val fresh = File(app.filesDir, "scans/new.jpg").apply { writeText("new") }
        PageRepository.addPage(a.copy(processedImagePath = old.absolutePath))
        PageRepository.updatePage(a.copy(processedImagePath = fresh.absolutePath))
        assertFalse(old.exists()); assertTrue(fresh.exists()); assertTrue(File(a.originalImagePath).exists())
    }
    @Test fun invalidReorderDoesNotChangeOrder() {
        PageRepository.addPage(page("a")); PageRepository.addPage(page("b"))
        PageRepository.reorderPages(-1, 8)
        assertEquals(listOf("a", "b"), PageRepository.pages.value.map { it.id })
    }
}
