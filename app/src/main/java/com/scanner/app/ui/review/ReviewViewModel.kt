package com.scanner.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanner.app.data.image.PageEditor
import com.scanner.app.data.repository.PageRepository
import com.scanner.app.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReviewViewModel : ViewModel() {
    val pages = PageRepository.pages
    private val _selectedPageId = MutableStateFlow<String?>(null)
    val selectedPageId = _selectedPageId.asStateFlow()
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()
    private val _imageVersion = MutableStateFlow(0)
    val imageVersion = _imageVersion.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    fun dismissError() { _error.value = null }
    fun selectPage(pageId: String?) { _selectedPageId.value = pageId }

    private fun edit(ids: Collection<String>, onComplete: () -> Unit = {}, change: (ScannedPage) -> ScannedPage) {
        if (_isProcessing.value) return
        _isProcessing.value = true
        viewModelScope.launch {
            try {
                ids.forEach { PageEditor.edit(it, change) }
                _imageVersion.value++
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.message ?: "Image processing failed" }
            finally { _isProcessing.value = false; onComplete() }
        }
    }
    fun rotatePage(pageId: String, onComplete: () -> Unit = {}) = edit(listOf(pageId), onComplete) {
        it.copy(rotation = (it.rotation + 90) % 360)
    }
    fun setPageFilter(pageId: String, newFilter: ImageFilter) = edit(listOf(pageId)) { it.copy(filter = newFilter) }
    fun setBatchFilter(pageIds: Collection<String>, newFilter: ImageFilter, onComplete: () -> Unit = {}) =
        edit(pageIds.toList(), onComplete) { it.copy(filter = newFilter) }
    fun setPageAspectRatio(pageId: String, preset: AspectRatioPreset) =
        edit(listOf(pageId)) { it.copy(targetAspectRatio = preset.ratio) }
    fun setPageCustomRatio(pageId: String, customRatio: Float) =
        edit(listOf(pageId)) { it.copy(targetAspectRatio = customRatio) }
    fun setBatchAspectRatio(pageIds: Collection<String>, preset: AspectRatioPreset, onComplete: () -> Unit = {}) =
        edit(pageIds.toList(), onComplete) { it.copy(targetAspectRatio = preset.ratio) }
    fun setBatchCustomRatio(pageIds: Collection<String>, customRatio: Float, onComplete: () -> Unit = {}) =
        edit(pageIds.toList(), onComplete) { it.copy(targetAspectRatio = customRatio) }
    fun deletePage(pageId: String) = deleteBatchPages(listOf(pageId))
    fun deleteBatchPages(pageIds: Collection<String>) {
        if (_isProcessing.value) return
        val ids = pageIds.toList()
        if (_selectedPageId.value in ids) _selectedPageId.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try { ids.forEach(PageRepository::deletePage) }
            catch (e: Exception) { _error.value = e.message ?: "Delete failed" }
        }
    }
    fun reorderPages(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) { PageRepository.reorderPages(fromIndex, toIndex) }
    }
}
