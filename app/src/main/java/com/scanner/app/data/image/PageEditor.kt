package com.scanner.app.data.image

import com.scanner.app.data.repository.PageRepository
import com.scanner.app.domain.model.ScannedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Shared by review and crop so overlapping edits cannot commit stale metadata. */
object PageEditor {
    private val mutex = Mutex()
    suspend fun edit(id: String, transform: (ScannedPage) -> ScannedPage) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = PageRepository.getPage(id) ?: return@withContext
            val rendered = DocumentProcessor().process(transform(current))
            try {
                if (PageRepository.getPage(id) != null) PageRepository.updatePage(rendered)
                else File(rendered.imagePath).delete()
            } catch (e: Throwable) { File(rendered.imagePath).delete(); throw e }
        }
    }
}
