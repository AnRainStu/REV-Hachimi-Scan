package com.scanner.app.data.repository

import com.scanner.app.domain.model.ScannedPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

object PageRepository {
    private val _pages = MutableStateFlow<List<ScannedPage>>(emptyList())
    val pages: StateFlow<List<ScannedPage>> = _pages.asStateFlow()

    fun addPage(page: ScannedPage) {
        _pages.value = _pages.value + page
    }

    fun getPage(id: String): ScannedPage? {
        return _pages.value.find { it.id == id }
    }

    fun updatePage(page: ScannedPage) {
        _pages.value = _pages.value.map { if (it.id == page.id) page else it }
    }

    fun deletePage(id: String) {
        _pages.value = _pages.value.filter { it.id != id }
    }

    fun reorderPages(fromIndex: Int, toIndex: Int) {
        val list = _pages.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            Collections.swap(list, fromIndex, toIndex)
            _pages.value = list
        }
    }

    fun clear() {
        _pages.value = emptyList()
    }
}
