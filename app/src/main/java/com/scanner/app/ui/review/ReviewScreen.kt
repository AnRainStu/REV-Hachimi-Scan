package com.scanner.app.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanner.app.R
import com.scanner.app.domain.model.*
import com.scanner.app.ui.components.AspectRatioBanner
import com.scanner.app.ui.components.ScanImage
import com.scanner.app.ui.export.ExportDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onNavigateToCamera: () -> Unit, onNavigateToCrop: (String) -> Unit,
    onExport: () -> Unit = {}, viewModel: ReviewViewModel = viewModel()) {
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedPageId.collectAsStateWithLifecycle()
    val busy by viewModel.isProcessing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var selecting by rememberSaveable { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var exportIds by remember { mutableStateOf<List<String>?>(null) }
    var deleteIds by remember { mutableStateOf<List<String>?>(null) }
    var batchPanel by remember { mutableStateOf(false) }
    val page = pages.find { it.id == selectedId }
    BackHandler(selecting) { selecting = false; selected.clear() }
    LaunchedEffect(pages) { selected.retainAll(pages.map { it.id }.toSet()) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(if (selecting) R.string.multi_select else R.string.library_title)) },
            actions = { if (pages.isNotEmpty()) TextButton(onClick = {
                selecting = !selecting; selected.clear()
            }, enabled = !busy) { Text(stringResource(if (selecting) R.string.cancel else R.string.multi_select)) } })
    }, floatingActionButton = {
        if (!selecting) ExtendedFloatingActionButton(onClick = onNavigateToCamera, icon = { Icon(Icons.Default.AddAPhoto, null) },
            text = { Text(stringResource(R.string.camera_title)) })
    }, bottomBar = {
        if (pages.isNotEmpty()) Surface(tonalElevation = 2.dp) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (selecting) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.selected_count, selected.size), modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (selected.size == pages.size) selected.clear() else { selected.clear(); selected.addAll(pages.map { it.id }) }
                    }) { Text(stringResource(if (selected.size == pages.size) R.string.deselect_all else R.string.select_all)) }
                    IconButton(onClick = { batchPanel = true }, enabled = selected.isNotEmpty() && !busy) { Icon(Icons.Default.Tune, stringResource(R.string.filter)) }
                    IconButton(onClick = { deleteIds = selected.toList() }, enabled = selected.isNotEmpty() && !busy) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                }
                Button(onClick = { exportIds = if (selecting) selected.toList() else pages.map { it.id } },
                    enabled = !busy && (!selecting || selected.isNotEmpty()), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.IosShare, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.export_document))
                }
            }
        }
    }) { padding ->
        if (pages.isEmpty()) Column(Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Description, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.empty_description), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNavigateToCamera) { Text(stringResource(R.string.camera_title)) }
        } else LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 88.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text(stringResource(R.string.review_title, pages.size), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.library_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(pages, key = { _, item -> item.id }) { index, item ->
                val checked = item.id in selected
                OutlinedCard(onClick = {
                    if (selecting) { if (checked) selected.remove(item.id) else selected.add(item.id) }
                    else viewModel.selectPage(item.id)
                }, enabled = !busy, border = BorderStroke(if (checked) 2.dp else 1.dp,
                    if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    ScanImage(item.imagePath, stringResource(R.string.page_number, index + 1),
                        Modifier.fillMaxWidth().aspectRatio(.78f).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp), 800)
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.page_number, index + 1), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (selecting) Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        else IconButton(onClick = { deleteIds = listOf(item.id) }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.delete_page)) }
                    }
                }
            }
        }
    }
    if (busy) Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp)); Text(stringResource(R.string.processing))
            }
        }
    }
    page?.let { item -> Dialog(onDismissRequest = { if (!busy) viewModel.selectPage(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var zoom by remember(item.id) { mutableFloatStateOf(1f) }
        var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text(stringResource(R.string.page_number, pages.indexOf(item) + 1)) },
                navigationIcon = { IconButton(onClick = { viewModel.selectPage(null) }, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close)) } },
                actions = { IconButton(onClick = { deleteIds = listOf(item.id) }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.delete)) } })
        }, bottomBar = {
            Surface(tonalElevation = 2.dp) { Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                FilterRow(item.filter, !busy) { viewModel.setPageFilter(item.id, it) }
                AspectRatioBanner(AspectRatioPreset.fromRatio(item.targetAspectRatio), item.targetAspectRatio,
                    { if (!busy) viewModel.setPageAspectRatio(item.id, it) }, { if (!busy) viewModel.setPageCustomRatio(item.id, it) })
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.selectPage(null); onNavigateToCrop(item.id) }, enabled = !busy) { Text(stringResource(R.string.re_crop)) }
                    TextButton(onClick = { viewModel.rotatePage(item.id) }, enabled = !busy) { Text(stringResource(R.string.rotate)) }
                    Spacer(Modifier.weight(1f))
                    val index = pages.indexOf(item)
                    IconButton(onClick = { viewModel.reorderPages(index, index - 1) }, enabled = !busy && index > 0) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.move_previous)) }
                    IconButton(onClick = { viewModel.reorderPages(index, index + 1) }, enabled = !busy && index < pages.lastIndex) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.move_next)) }
                }
            } }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).clipToBounds().pointerInput(item.id) {
                detectTransformGestures { _, pan, factor, _ ->
                    zoom = (zoom * factor).coerceIn(1f, 5f)
                    val maxX = size.width * (zoom - 1) / 2; val maxY = size.height * (zoom - 1) / 2
                    offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                }
            }) {
                ScanImage(item.imagePath, stringResource(R.string.open), Modifier.fillMaxSize().padding(20.dp).graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                }, 2400)
            }
        }
    } }
    if (batchPanel) ModalBottomSheet(onDismissRequest = { batchPanel = false }) {
        Text(stringResource(R.string.selected_count, selected.size), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
        FilterRow(null, !busy) { viewModel.setBatchFilter(selected.toList(), it) }
        AspectRatioBanner(AspectRatioPreset.CUSTOM, null,
            { if (!busy) viewModel.setBatchAspectRatio(selected.toList(), it) },
            { if (!busy) viewModel.setBatchCustomRatio(selected.toList(), it) })
        Spacer(Modifier.height(24.dp))
    }
    deleteIds?.let { ids -> AlertDialog(onDismissRequest = { deleteIds = null },
        title = { Text(stringResource(R.string.delete_selected_title)) }, text = { Text(stringResource(R.string.delete_selected_confirm, ids.size)) },
        confirmButton = { TextButton(onClick = { viewModel.deleteBatchPages(ids); deleteIds = null }, enabled = !busy) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleteIds = null }) { Text(stringResource(R.string.cancel)) } }) }
    exportIds?.let { ids -> ExportDialog(pages.filter { it.id in ids }, { exportIds = null }) }
    error?.let { message -> AlertDialog(onDismissRequest = viewModel::dismissError, title = { Text(stringResource(R.string.operation_failed)) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.close)) } }) }
}

@Composable
private fun FilterRow(selected: ImageFilter?, enabled: Boolean, onSelect: (ImageFilter) -> Unit) {
    val labels = mapOf(ImageFilter.ORIGINAL to R.string.filter_original, ImageFilter.MAGIC_COLOR to R.string.filter_magic,
        ImageFilter.GRAYSCALE to R.string.filter_grayscale, ImageFilter.BW to R.string.filter_bw)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageFilter.entries.forEach { filter -> FilterChip(selected == filter, { onSelect(filter) }, enabled = enabled,
            label = { Text(stringResource(labels.getValue(filter))) }) }
    }
}
