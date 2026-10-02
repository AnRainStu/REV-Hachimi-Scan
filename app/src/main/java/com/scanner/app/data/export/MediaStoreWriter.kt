package com.scanner.app.data.export

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.OutputStream

/** Keep incomplete exports hidden; rollback every row if a batch fails. Android 10+. */
internal class MediaStoreWriter(private val resolver: ContentResolver) {
    private val inserted = mutableListOf<Uri>()
    fun write(collection: Uri, directory: String, name: String, mimeType: String, writer: (OutputStream) -> Unit): Uri {
        val uri = checkNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })) { "Cannot create export file" }
        inserted.add(uri)
        checkNotNull(resolver.openOutputStream(uri)) { "Cannot open export file" }.use(writer)
        return uri
    }
    fun publish() {
        inserted.forEach { uri ->
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1) { "Cannot publish export file" }
        }
    }
    fun rollback() {
        inserted.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
    }
    fun displayName(uri: Uri): String = resolver.query(
        uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
        ?: error("Cannot resolve export name")
}
