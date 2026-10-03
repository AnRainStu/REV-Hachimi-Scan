package com.scanner.app.ui.crop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanner.app.ui.components.GlassScaffold
import com.scanner.app.ui.components.GlassSurface
import com.scanner.app.R
import com.scanner.app.domain.model.AspectRatioPreset
import com.scanner.app.domain.model.ImageFilter
import com.scanner.app.ui.components.CustomRatioDialog
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    pageId: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    viewModel: CropViewModel = viewModel()
) {
    LaunchedEffect(pageId) {
        viewModel.loadPage(pageId)
    }

    val currentQuad by viewModel.currentQuad.collectAsState()
    val detectedQuad by viewModel.detectedQuad.collectAsState()
    val horizontalLines by viewModel.horizontalLines.collectAsState()
    val verticalLines by viewModel.verticalLines.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val selectedRatio by viewModel.selectedRatio.collectAsState()
    val customRatioValue by viewModel.customRatioValue.collectAsState()
    val imagePath by viewModel.imagePath.collectAsState()
    val imageVersion by viewModel.imageVersion.collectAsState()
    val error by viewModel.error.collectAsState()
    var isSaving by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val rotationAngle = remember { Animatable(0f) }
    var isRotating by remember { mutableStateOf(false) }

    error?.let { message -> AlertDialog(onDismissRequest = viewModel::dismissError,
        title = { Text(stringResource(R.string.operation_failed)) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.close)) } }) }

    GlassScaffold(
        topBar = {
            Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CropGlassTool(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel),
                        enabled = !isSaving, onClick = onCancel)
                    Text(stringResource(R.string.crop_title), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    GlassSurface(cornerRadius = 24.dp, tint = Color(0xFF005ACB), strong = true,
                        contentColor = Color.White) {
                        TextButton(enabled = !isSaving && !isRotating,
                            onClick = {
                                isSaving = true
                                viewModel.confirmCrop { success ->
                                    isSaving = false
                                    if (success) onConfirm()
                                }
                            }, shape = CircleShape,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White,
                                disabledContentColor = Color.White.copy(alpha = .5f)),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                            if (isSaving) CircularProgressIndicator(Modifier.size(18.dp),
                                color = Color.White, strokeWidth = 2.dp)
                            else Text(stringResource(R.string.confirm), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CropGlassTool(Icons.Default.Refresh, stringResource(R.string.rotate_90),
                        enabled = !isRotating && !isSaving, onClick = {
                        isRotating = true
                        coroutineScope.launch {
                            val animJob = launch {
                                rotationAngle.animateTo(targetValue = 90f,
                                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing))
                            }
                            viewModel.rotateImage {
                                coroutineScope.launch {
                                    animJob.join()
                                    rotationAngle.snapTo(0f)
                                    isRotating = false
                                }
                            }
                        }
                    })
                    CropGlassTool(Icons.Default.CropFree, stringResource(R.string.full_image),
                        enabled = !isRotating && !isSaving, onClick = viewModel::resetToFullImage)
                    CropGlassTool(Icons.Default.AutoFixHigh, stringResource(R.string.auto_detect),
                        enabled = !isRotating && !isSaving, onClick = viewModel::reDetect,
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                    CropRatioStrip(selectedRatio, customRatioValue,
                        onSelectRatio = viewModel::setAspectRatio, onSelectCustomRatio = viewModel::setCustomRatio,
                        enabled = !isRotating && !isSaving)
                }
                GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 26.dp) {
                    Row(Modifier.fillMaxWidth().padding(5.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CropFilterOption(stringResource(R.string.filter_original), Icons.Default.Image,
                            isSelected = selectedFilter == ImageFilter.ORIGINAL, modifier = Modifier.weight(1f),
                            enabled = !isRotating && !isSaving, onClick = { viewModel.setFilter(ImageFilter.ORIGINAL) })
                        CropFilterOption(stringResource(R.string.filter_magic), Icons.Default.AutoAwesome,
                            isSelected = selectedFilter == ImageFilter.MAGIC_COLOR, modifier = Modifier.weight(1f),
                            enabled = !isRotating && !isSaving, onClick = { viewModel.setFilter(ImageFilter.MAGIC_COLOR) })
                        CropFilterOption(stringResource(R.string.filter_bw), Icons.Default.Contrast,
                            isSelected = selectedFilter == ImageFilter.BW, modifier = Modifier.weight(1f),
                            enabled = !isRotating && !isSaving, onClick = { viewModel.setFilter(ImageFilter.BW) })
                        CropFilterOption(stringResource(R.string.filter_grayscale), Icons.Default.FilterBAndW,
                            isSelected = selectedFilter == ImageFilter.GRAYSCALE, modifier = Modifier.weight(1f),
                            enabled = !isRotating && !isSaving, onClick = { viewModel.setFilter(ImageFilter.GRAYSCALE) })
                    }
                }
            }
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val containerW = constraints.maxWidth.toFloat()
            val containerH = constraints.maxHeight.toFloat()

            val preview by produceState<Pair<Pair<Float, Float>, Bitmap?>?>(null, imagePath, imageVersion) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    imagePath?.let { getUprightDimensions(it) to loadUprightBitmap(it) }
                }
            }
            val (origW, origH) = preview?.first ?: Pair(0f, 0f)
            val bitmap = preview?.second

            LaunchedEffect(bitmap) {
                if (bitmap != null) {
                    viewModel.onBitmapLoaded(bitmap)
                }
            }

            // Continuous rotation scale compensation:
            // When rotating 90 deg, fit the rotated aspect ratio without clipping or layout jumping
            val targetScale = remember(bitmap, containerW, containerH) {
                if (bitmap != null && bitmap.width > 0 && bitmap.height > 0 && containerW > 0 && containerH > 0) {
                    val bw = bitmap.width.toFloat()
                    val bh = bitmap.height.toFloat()
                    val sOrig = kotlin.math.min(containerW / bw, containerH / bh)
                    val sRot = kotlin.math.min(containerW / bh, containerH / bw)
                    if (sOrig > 0f) sRot / sOrig else 1.0f
                } else 1.0f
            }

            val rotProgress = (rotationAngle.value / 90f).coerceIn(0f, 1f)
            val dynamicScale = 1.0f + (targetScale - 1.0f) * rotProgress

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = dynamicScale
                        scaleY = dynamicScale
                        rotationZ = rotationAngle.value
                    }
            ) {
                QuadCropView(
                    bitmap = bitmap,
                    quad = currentQuad,
                    detectedQuad = detectedQuad,
                    horizontalLines = horizontalLines,
                    verticalLines = verticalLines,
                    originalWidth = origW,
                    originalHeight = origH,
                    onCornerUpdated = { cornerIndex, newPosition ->
                        viewModel.updateCorner(cornerIndex, newPosition)
                    },
                    onEdgeUpdated = { edgeIndex, deltaX, deltaY ->
                        viewModel.updateEdge(edgeIndex, deltaX, deltaY)
                    },
                    onQuadChanged = { newQuad ->
                        viewModel.updateQuad(newQuad)
                    },
                    onTapToSnap = { x, y ->
                        viewModel.tapToSnap(x, y)
                    },
                    modifier = Modifier.fillMaxSize().padding(20.dp)
                )
            }

            AnimatedVisibility(
                visible = isSaving,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f)),
                    contentAlignment = Alignment.Center) {
                    Surface(Modifier.padding(24.dp), shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                        Row(Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
                            Text(stringResource(R.string.processing),
                                color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CropGlassTool(icon: ImageVector, description: String, onClick: () -> Unit,
    enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface) {
    GlassSurface(Modifier.size(48.dp), cornerRadius = 24.dp) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
            Icon(icon, description, Modifier.size(22.dp), tint = tint.copy(alpha = if (enabled) 1f else .4f))
        }
    }
}

@Composable
private fun CropFilterOption(label: String, icon: ImageVector, isSelected: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = modifier.background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = .14f)
            else Color.Transparent, RoundedCornerShape(22.dp)).semantics { selected = isSelected }, shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Icon(icon, null, Modifier.size(20.dp), tint = color.copy(alpha = if (enabled) 1f else .4f))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = color.copy(alpha = if (enabled) 1f else .4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CropRatioStrip(selected: AspectRatioPreset, customRatio: Float?,
    onSelectRatio: (AspectRatioPreset) -> Unit, onSelectCustomRatio: (Float) -> Unit,
    enabled: Boolean) {
    var custom by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        AspectRatioPreset.entries.forEach { preset ->
            val active = selected == preset && (preset != AspectRatioPreset.CUSTOM || customRatio == null)
            TextButton(onClick = { onSelectRatio(preset) }, enabled = enabled,
                modifier = Modifier.background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .14f)
                    else Color.Transparent, CircleShape).semantics { this.selected = active }, shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                Text(stringResource(if (preset == AspectRatioPreset.CUSTOM) R.string.ratio_free else preset.titleRes),
                    color = (if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        .copy(alpha = if (enabled) 1f else .4f), fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
        val customActive = selected == AspectRatioPreset.CUSTOM && customRatio != null
        TextButton(onClick = { custom = true }, enabled = enabled, shape = CircleShape,
            modifier = Modifier.background(if (customActive) MaterialTheme.colorScheme.primary.copy(alpha = .14f)
                else Color.Transparent, CircleShape).semantics { this.selected = customActive },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.ratio_custom),
                color = (if (customActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    .copy(alpha = if (enabled) 1f else .4f), fontWeight = if (customActive) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
    if (custom) CustomRatioDialog(customRatio, { custom = false }) {
        custom = false
        onSelectCustomRatio(it)
    }
}

private fun getUprightDimensions(path: String): Pair<Float, Float> {
    val f = File(path)
    if (!f.exists()) return Pair(0f, 0f)
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, boundsOpts)
    if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return Pair(0f, 0f)
    val exif = try { ExifInterface(path) } catch (e: Exception) { null }
    val orientation = exif?.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    ) ?: ExifInterface.ORIENTATION_NORMAL
    return if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) {
        Pair(boundsOpts.outHeight.toFloat(), boundsOpts.outWidth.toFloat())
    } else {
        Pair(boundsOpts.outWidth.toFloat(), boundsOpts.outHeight.toFloat())
    }
}

private fun loadUprightBitmap(path: String, maxDimension: Int = 4096): Bitmap? {
    val f = File(path)
    if (!f.exists()) return null
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, boundsOpts)
    if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

    var inSampleSize = 1
    val maxSide = max(boundsOpts.outWidth, boundsOpts.outHeight)
    while ((maxSide / inSampleSize) > maxDimension) {
        inSampleSize *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply {
        this.inSampleSize = inSampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bmp = BitmapFactory.decodeFile(path, decodeOpts) ?: return null

    return try {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated != bmp) {
            bmp.recycle()
        }
        rotated
    } catch (e: Exception) {
        bmp
    }
}
