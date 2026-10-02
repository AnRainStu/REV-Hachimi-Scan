package com.scanner.app.data.repository

import android.content.Context
import com.scanner.app.domain.model.ScannedPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Publish changes only after the SQLite transaction commits. */
object PageRepository {
    private val _pages = MutableStateFlow<List<ScannedPage>>(emptyList())
    val pages: StateFlow<List<ScannedPage>> = _pages.asStateFlow()
    private lateinit var store: PageStore
    private lateinit var scanDir: File

    @Synchronized fun initialize(context: Context) {
        if (::store.isInitialized) store.close()
        store = PageStore(context.applicationContext)
        scanDir = File(context.filesDir, "scans").apply { check(isDirectory || mkdirs()) }
        _pages.value = store.load()
        val referenced = _pages.value.flatMap(::paths).toSet()
        scanDir.listFiles()?.filter { it.isFile && it.extension == "jpg" && it.absolutePath !in referenced }
            ?.forEach { it.delete() }
    }
    @Synchronized fun addPage(page: ScannedPage) {
        require(_pages.value.none { it.id == page.id }) { "Duplicate page" }
        commit(_pages.value + page)
    }
    fun getPage(id: String): ScannedPage? = _pages.value.find { it.id == id }
    @Synchronized fun updatePage(page: ScannedPage) {
        val previous = getPage(page.id) ?: return
        commit(_pages.value.map { if (it.id == page.id) page else it })
        cleanup(previous, _pages.value)
    }
    @Synchronized fun deletePage(id: String) {
        val removed = getPage(id) ?: return
        commit(_pages.value.filter { it.id != id })
        cleanup(removed, _pages.value)
    }
    @Synchronized fun reorderPages(fromIndex: Int, toIndex: Int) {
        val list = _pages.value.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return
        list.add(toIndex, list.removeAt(fromIndex))
        commit(list)
    }
    @Synchronized fun clear() {
        val removed = _pages.value
        commit(emptyList())
        removed.forEach { cleanup(it, emptyList()) }
    }
    private fun commit(pages: List<ScannedPage>) {
        check(::store.isInitialized) { "Initialize PageRepository in Application" }
        store.save(pages)
        _pages.value = pages
    }
    private fun paths(page: ScannedPage) = listOfNotNull(
        page.originalImagePath, page.processedImagePath, page.thumbnailPath
    )
    private fun cleanup(page: ScannedPage, retained: List<ScannedPage>) {
        val referenced = retained.flatMap(::paths).toSet()
        paths(page).distinct().filterNot { it in referenced }.forEach { path ->
            val file = File(path)
            // Never delete user-owned files outside app scan storage.
            if (file.canonicalFile.parentFile == scanDir.canonicalFile) file.delete()
        }
    }
}
