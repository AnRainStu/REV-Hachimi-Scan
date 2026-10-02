package com.scanner.app

import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.scanner.app.data.export.FolderExporter
import com.scanner.app.data.export.PdfExporter
import com.scanner.app.domain.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExportTest {
    private lateinit var app: Application
    private lateinit var provider: TestMediaProvider
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        provider = TestMediaProvider(app.cacheDir)
        ShadowContentResolver.registerProviderInternal("media", provider)
    }
    private fun page(name: String) = ScannedPage(originalImagePath = File(app.cacheDir, name).apply { writeText("jpeg") }.absolutePath)
    @Test fun successfulBatchPublishesAllRows() {
        val result = FolderExporter(app).export(listOf(page("a.jpg"), page("b.jpg")), ExportConfig(name = "receipt"))
        assertEquals(2, result.uris.size)
        assertTrue(provider.rows.values.all { it.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0 })
        assertEquals("Pictures/HachiCam/receipt", result.location)
    }
    @Test fun failedInsertRollsBackPriorPages() {
        provider.failInsertAt = 2
        assertThrows(IllegalStateException::class.java) {
            FolderExporter(app).export(listOf(page("a.jpg"), page("b.jpg")), ExportConfig(name = "receipt"))
        }
        assertTrue(provider.rows.isEmpty())
    }
    @Test fun missingSecondPageRollsBackFirstPage() {
        val missing = ScannedPage(originalImagePath = "/missing.jpg")
        assertThrows(IllegalStateException::class.java) {
            FolderExporter(app).export(listOf(page("a.jpg"), missing), ExportConfig(name = "receipt"))
        }
        assertTrue(provider.rows.isEmpty())
    }
    @Test fun failedStreamRollsBackNewRow() {
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerOutputStream(Uri.parse("content://media/export/1"), object : java.io.OutputStream() {
            override fun write(value: Int) { throw java.io.IOException("Injected disk failure") }
        })
        assertThrows(java.io.IOException::class.java) { FolderExporter(app).export(listOf(page("a.jpg")), ExportConfig(name = "receipt")) }
        assertTrue(provider.rows.isEmpty())
    }
    @Test fun failedPublishRollsBackEntireBatch() {
        provider.failPublish = true
        assertThrows(IllegalStateException::class.java) { FolderExporter(app).export(listOf(page("a.jpg")), ExportConfig(name = "receipt")) }
        assertTrue(provider.rows.isEmpty())
    }
    @Test fun rejectsEmptyExportAndUnsafeNames() {
        assertThrows(IllegalArgumentException::class.java) { PdfExporter(app).export(emptyList(), ExportConfig()) }
        for (name in listOf("", "..", "../../escape", "a\\b", "a:b", "x".repeat(101))) {
            assertThrows(IllegalArgumentException::class.java) { ExportConfig(name = name).validatedName() }
        }
        assertEquals("合同 2026", ExportConfig(name = " 合同 2026 ").validatedName())
    }
}

private class TestMediaProvider(private val directory: File) : ContentProvider() {
    val rows = linkedMapOf<Uri, ContentValues>()
    var failInsertAt = 0; var failStream = false; var failPublish = false
    private var counter = 0
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        counter++
        if (counter == failInsertAt) return null
        return Uri.parse("content://media/export/$counter").also { created ->
            rows[created] = ContentValues(values)
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (failStream) return null
        return ParcelFileDescriptor.open(File(directory, "export-${uri.lastPathSegment}"),
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (failPublish) return 0
        val row = rows[uri] ?: return 0; row.putAll(values); return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = if (rows.remove(uri) != null) 1 else 0
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)).apply { rows[uri]?.let { addRow(arrayOf(it.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))) } }
    override fun getType(uri: Uri) = "image/jpeg"
}
