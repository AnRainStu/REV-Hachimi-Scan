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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanner.app.R
import com.scanner.app.domain.model.*
import com.scanner.app.ui.components.*
import com.scanner.app.ui.export.ExportDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val filterLabels = mapOf(ImageFilter.ORIGINAL to R.string.filter_original,
    ImageFilter.MAGIC_COLOR to R.string.filter_magic, ImageFilter.GRAYSCALE to R.string.filter_grayscale, ImageFilter.BW to R.string.filter_bw)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onNavigateToCamera: () -> Unit, onNavigateToCrop: (String) -> Unit,
    onExport: () -> Unit = {}, onNavigateToSettings: () -> Unit = {}, viewModel: ReviewViewModel = viewModel()) {
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
    val gridState = rememberLazyGridState()
    BackHandler(selecting) { selecting = false; selected.clear() }
    LaunchedEffect(pages) {
        selected.retainAll(pages.map { it.id }.toSet())
        if (pages.isEmpty()) selecting = false
    }
    Box(Modifier.fillMaxSize()) {
        if (page == null) GlassScaffold(topBar = {
            Row(Modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.DocumentScanner, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (pages.isNotEmpty()) GlassButton(onClick = { selecting = !selecting; selected.clear() }, enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                    Text(stringResource(if (selecting) R.string.cancel else R.string.multi_select), style = MaterialTheme.typography.labelLarge)
                }
                GlassButton(onNavigateToSettings, Modifier.size(52.dp), enabled = !busy, contentPadding = PaddingValues(14.dp)) {
                    Icon(Icons.Default.Settings, stringResource(R.string.settings), Modifier.size(24.dp))
                }
            }
        }, bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selecting) GlassSurface(strong = true, cornerRadius = 26.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.selected_count, selected.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            if (selected.size == pages.size) selected.clear() else { selected.clear(); selected.addAll(pages.map { it.id }) }
                        }) { Text(stringResource(if (selected.size == pages.size) R.string.deselect_all else R.string.select_all)) }
                        IconButton(onClick = { batchPanel = true }, enabled = selected.isNotEmpty() && !busy) { Icon(Icons.Default.Tune, stringResource(R.string.filter)) }
                        IconButton(onClick = { deleteIds = selected.toList() }, enabled = selected.isNotEmpty() && !busy) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.delete)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (pages.isNotEmpty()) GlassButton(onClick = { exportIds = if (selecting) selected.toList() else pages.map { it.id } },
                        enabled = !busy && (!selecting || selected.isNotEmpty()), strong = true) {
                        Icon(Icons.Default.IosShare, null, Modifier.size(22.dp)); Text(stringResource(R.string.export), style = MaterialTheme.typography.labelLarge)
                    }
                    if (!selecting) GlassButton(onNavigateToCamera, enabled = !busy, primary = true,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 17.dp)) {
                        Icon(Icons.Default.AddAPhoto, null, Modifier.size(24.dp)); Text(stringResource(R.string.camera_title), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }) { padding ->
            if (pages.isEmpty()) Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                Spacer(Modifier.weight(1f)); EmptyPaperSymbol(); Spacer(Modifier.height(28.dp))
                Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.empty_description), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1.3f))
            } else LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), state = gridState, modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = padding.calculateTopPadding() + 10.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) { Column {
                    Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pluralStringResource(R.plurals.library_count, pages.size, pages.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Default.Lock, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.library_local), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(26.dp)); Text(stringResource(R.string.library_recent), style = MaterialTheme.typography.titleMedium)
                } }
                itemsIndexed(pages, key = { _, item -> item.id }) { index, item ->
                    DocumentCard(item, index, item.id in selected, selecting, !busy) {
                        if (selecting) { if (item.id in selected) selected.remove(item.id) else selected.add(item.id) }
                        else viewModel.selectPage(item.id)
                    }
                }
            }
        }
        page?.let { item -> PageEditor(item, pages, busy, viewModel, { viewModel.selectPage(null) },
            { viewModel.selectPage(null); onNavigateToCrop(item.id) }, { deleteIds = listOf(item.id) }) }
    }
    if (busy) Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(28.dp)) { Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp); Text(stringResource(R.string.processing))
        } }
    }
    if (batchPanel) ModalBottomSheet(onDismissRequest = { batchPanel = false }) {
        Text(stringResource(R.string.selected_count, selected.size), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(24.dp))
        FilterRow(null, !busy) { viewModel.setBatchFilter(selected.toList(), it) }
        AspectRatioBanner(AspectRatioPreset.CUSTOM, null,
            { if (!busy) viewModel.setBatchAspectRatio(selected.toList(), it) }, { if (!busy) viewModel.setBatchCustomRatio(selected.toList(), it) })
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
private fun DocumentCard(page: ScannedPage, index: Int, checked: Boolean, selecting: Boolean, enabled: Boolean, onOpen: () -> Unit) {
    val date = remember(page.createdAt, Locale.getDefault()) { SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(page.createdAt)) }
    Card(onClick = onOpen, enabled = enabled, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = if (checked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
        Box(Modifier.fillMaxWidth().aspectRatio(.74f).background(Color.White)) {
            ScanImage(page.imagePath, stringResource(R.string.page_number, index + 1), Modifier.fillMaxSize().padding(5.dp), 1000)
            if (selecting) Surface(Modifier.align(Alignment.TopEnd).padding(10.dp).size(26.dp), shape = RoundedCornerShape(13.dp),
                color = if (checked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .9f),
                border = if (checked) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                if (checked) Icon(Icons.Default.Check, null, Modifier.padding(5.dp), tint = Color.White)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(stringResource(R.string.page_number, index + 1), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text("${stringResource(filterLabels.getValue(page.filter))} · $date", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyPaperSymbol() {
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.size(88.dp, 112.dp).graphicsLayer { rotationZ = -13f; translationX = -18f },
            shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {}
        Surface(Modifier.size(98.dp, 120.dp), shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
            Column(Modifier.padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Description, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .3f), RoundedCornerShape(2.dp)))
                Box(Modifier.width(38.dp).height(3.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .15f), RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun PageEditor(item: ScannedPage, pages: List<ScannedPage>, busy: Boolean, viewModel: ReviewViewModel,
    onClose: () -> Unit, onCrop: () -> Unit, onDelete: () -> Unit) {
    BackHandler { if (!busy) onClose() }
    var zoom by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var showRatios by remember(item.id) { mutableStateOf(false) }
    GlassScaffold(topBar = {
        Row(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassButton(onClose, Modifier.size(52.dp), enabled = !busy, contentPadding = PaddingValues(14.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close), Modifier.size(24.dp))
            }
            Text(stringResource(R.string.page_number, pages.indexOf(item) + 1), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            GlassButton(onDelete, Modifier.size(52.dp), enabled = !busy, contentPadding = PaddingValues(14.dp)) {
                Icon(Icons.Default.DeleteOutline, stringResource(R.string.delete), Modifier.size(24.dp))
            }
        }
    }, bottomBar = {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            GlassSurface(Modifier.fillMaxWidth(), strong = true, cornerRadius = 30.dp) { Column(Modifier.padding(vertical = 8.dp)) {
                FilterRow(item.filter, !busy) { viewModel.setPageFilter(item.id, it) }
                if (showRatios) AspectRatioBanner(AspectRatioPreset.fromRatio(item.targetAspectRatio), item.targetAspectRatio,
                    { if (!busy) viewModel.setPageAspectRatio(item.id, it) }, { if (!busy) viewModel.setPageCustomRatio(item.id, it) })
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onCrop, enabled = !busy) { Icon(Icons.Default.Crop, stringResource(R.string.re_crop)) }
                    IconButton(onClick = { viewModel.rotatePage(item.id) }, enabled = !busy) { Icon(Icons.Default.RotateRight, stringResource(R.string.rotate)) }
                    IconButton(onClick = { showRatios = !showRatios }, enabled = !busy) { Icon(Icons.Default.AspectRatio, stringResource(R.string.paper_size),
                        tint = if (showRatios) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    val index = pages.indexOf(item)
                    IconButton(onClick = { viewModel.reorderPages(index, index - 1) }, enabled = !busy && index > 0) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.move_previous)) }
                    IconButton(onClick = { viewModel.reorderPages(index, index + 1) }, enabled = !busy && index < pages.lastIndex) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.move_next)) }
                }
            } }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(vertical = 8.dp).clipToBounds().pointerInput(item.id) {
            detectTransformGestures { _, pan, factor, _ ->
                zoom = (zoom * factor).coerceIn(1f, 5f)
                val maxX = size.width * (zoom - 1) / 2; val maxY = size.height * (zoom - 1) / 2
                offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
            }
        }) { ScanImage(item.imagePath, stringResource(R.string.open), Modifier.fillMaxSize().padding(horizontal = 22.dp).graphicsLayer {
            scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
        }, 2400) }
    }
}

@Composable
private fun FilterRow(selected: ImageFilter?, enabled: Boolean, onSelect: (ImageFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ImageFilter.entries.forEach { filter -> TextButton(onClick = { onSelect(filter) }, enabled = enabled, shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = if (selected == filter) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.background(if (selected == filter) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(20.dp))) {
            Text(stringResource(filterLabels.getValue(filter)), style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected == filter) FontWeight.SemiBold else FontWeight.Normal)
        } }
    }
}
