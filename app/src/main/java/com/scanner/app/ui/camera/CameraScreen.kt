package com.scanner.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanner.app.ui.components.GlassSurface
import com.scanner.app.R
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(onNavigateToReview: () -> Unit, onNavigateToCrop: (String) -> Unit,
    onNavigateToSettings: () -> Unit, viewModel: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val chromeDensity = LocalDensity.current
    var topChrome by remember { mutableIntStateOf(0) }
    var bottomChrome by remember { mutableIntStateOf(0) }
    val view = androidx.compose.ui.platform.LocalView.current
    val window = (context as? android.app.Activity)?.window
    DisposableEffect(window, view) {
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        onDispose {
            if (oldStatus != null) controller.isAppearanceLightStatusBars = oldStatus
            if (oldNavigation != null) controller.isAppearanceLightNavigationBars = oldNavigation
        }
    }
    SideEffect { window?.let {
        androidx.core.view.WindowCompat.getInsetsController(it, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    } }
    val detected by viewModel.detectedQuad.collectAsStateWithLifecycle()
    val stable by viewModel.isStable.collectAsStateWithLifecycle()
    val busy by viewModel.isCapturing.collectAsStateWithLifecycle()
    val hdr by viewModel.fullHdrEnabled.collectAsStateWithLifecycle()
    val curved by viewModel.curvedModeEnabled.collectAsStateWithLifecycle()
    val count by viewModel.pageCount.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(busy) { }
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importFromUri(context, it, onNavigateToCrop) }
    }
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var torch by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(Unit) { viewModel.updateSettings(context) }
    LaunchedEffect(focus) { if (focus != null) { delay(1200); focus = null } }
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(value: Int) {
                if (value == ORIENTATION_UNKNOWN) return
                viewModel.imageCapture?.targetRotation = when (value) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF101114))) {
        if (allowed) {
            CameraPreview(viewModel, Modifier.fillMaxSize().pointerInput(preview, busy) {
                detectTapGestures { point ->
                    if (!busy) {
                        preview?.let { view -> viewModel.cameraControl?.startFocusAndMetering(
                            FocusMeteringAction.Builder(view.meteringPointFactory.createPoint(point.x, point.y))
                                .setAutoCancelDuration(3, TimeUnit.SECONDS).build()) }
                        focus = point
                    }
                }
            }) { preview = it }
            detected?.let { EdgeOverlay(it, curved, Modifier.fillMaxSize()) }
            focus?.let { point ->
                val density = LocalDensity.current
                Box(Modifier.offset(x = with(density) { point.x.toDp() } - 28.dp,
                    y = with(density) { point.y.toDp() } - 28.dp).size(56.dp)
                    .border(1.dp, Color.White, RoundedCornerShape(8.dp)))
            }
        } else {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(
                listOf(Color(0xFF203454), Color(0xFF101114)),
                radius = with(chromeDensity) { 360.dp.toPx() })))
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = with(chromeDensity) { topChrome.toDp() },
                bottom = with(chromeDensity) { bottomChrome.toDp() })) {
            val compact = maxHeight < 460.dp
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .padding(if (compact) 20.dp else 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp, Alignment.CenterVertically)) {
                GlassSurface(Modifier.size(if (compact) 48.dp else 80.dp), dark = true, cornerRadius = 28.dp) {
                    Icon(Icons.Default.PhotoCamera, null, tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(if (compact) 24.dp else 34.dp))
                }
                Text(stringResource(R.string.camera_permission_required), color = Color.White.copy(alpha = .72f),
                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                GlassSurface(dark = true, cornerRadius = 26.dp,
                    tint = Color(0xFF005ACB), contentColor = Color.White, strong = true) {
                    TextButton(onClick = { permission.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        shape = CircleShape, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
                        Text(stringResource(R.string.grant_permission))
                    }
                }
                TextButton(onClick = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}")))
                }) { Text(stringResource(R.string.settings), color = Color(0xFF7AB8FF)) }
            }
            }
        }
        Column(Modifier.align(Alignment.TopCenter).onSizeChanged { topChrome = it.height }
            .statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CameraGlassIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review),
                    onNavigateToReview, enabled = !busy)
                Text(stringResource(R.string.camera_title), color = Color.White,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                CameraGlassIcon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    stringResource(R.string.torch),
                    onClick = { torch = !torch; viewModel.cameraControl?.enableTorch(torch) },
                    enabled = allowed && !busy && viewModel.cameraInfo?.hasFlashUnit() == true,
                    active = torch)
                CameraGlassIcon(Icons.Default.Settings, stringResource(R.string.settings),
                    onNavigateToSettings, enabled = !busy)
            }
            if (allowed) GlassSurface(dark = true, cornerRadius = 24.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(6.dp).background(if (stable) Color(0xFF7FE3A8) else Color.White.copy(alpha = .6f), CircleShape))
                    Text(stringResource(if (stable) R.string.steady else R.string.hold_steady),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomChrome = it.height }
            .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (allowed) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassSurface(dark = true, cornerRadius = 24.dp) {
                    TextButton(onClick = viewModel::toggleCurvedMode, enabled = !busy,
                        modifier = Modifier.semantics { selected = curved },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                        Icon(Icons.Default.CropFree, null, Modifier.size(18.dp),
                            tint = if (curved) Color(0xFF8DC6FF) else Color.White)
                        Spacer(Modifier.width(7.dp))
                        Text(stringResource(R.string.curved_mode), color = if (curved) Color(0xFF8DC6FF) else Color.White,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                if (hdr) GlassSurface(dark = true, cornerRadius = 24.dp) {
                    Text(stringResource(R.string.full_hdr_title), Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CameraSideAction(Icons.Default.AddPhotoAlternate, stringResource(R.string.import_image),
                        onClick = { gallery.launch("image/*") }, enabled = !busy)
                }
                val shutterEnabled = allowed && preview != null && !busy
                val captureDescription = stringResource(R.string.capture)
                IconButton(onClick = { viewModel.capturePhoto(context, onNavigateToCrop) },
                    enabled = shutterEnabled, modifier = Modifier.size(82.dp)
                        .semantics { contentDescription = captureDescription }
                        .border(3.dp, Color.White.copy(alpha = if (allowed) 1f else .35f), CircleShape)) {
                    Box(Modifier.size(66.dp).background(Color.White.copy(alpha = if (allowed) 1f else .35f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Color(0xFF16181D), strokeWidth = 2.5.dp)
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CameraSideAction(Icons.Default.CollectionsBookmark, stringResource(R.string.review),
                        onClick = onNavigateToReview, enabled = !busy, badgeCount = count)
                }
            }
            if (busy) GlassSurface(dark = true, cornerRadius = 20.dp) {
                Text(stringResource(if (hdr) R.string.fusing_full_hdr else R.string.processing),
                    Modifier.padding(horizontal = 16.dp, vertical = 9.dp), style = MaterialTheme.typography.bodySmall,
                    color = Color.White, textAlign = TextAlign.Center)
            }
        }
    }
    error?.let { message -> AlertDialog(onDismissRequest = viewModel::dismissError,
        title = { Text(stringResource(R.string.operation_failed)) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.close)) } }) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                viewModel.updateSettings(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun CameraGlassIcon(icon: ImageVector, description: String, onClick: () -> Unit,
    enabled: Boolean = true, active: Boolean = false) {
    GlassSurface(Modifier.size(52.dp), dark = true, cornerRadius = 26.dp) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
            Icon(icon, description, tint = when {
                !enabled -> Color.White.copy(alpha = .4f)
                active -> Color(0xFF8DC6FF)
                else -> Color.White
            }, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun CameraSideAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean, badgeCount: Int? = null) {
    val description = if (badgeCount != null) stringResource(R.string.review_with_count, badgeCount) else label
    GlassSurface(Modifier.widthIn(min = 72.dp, max = 112.dp), dark = true, cornerRadius = 26.dp) {
        TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val color = Color.White.copy(alpha = if (enabled) 1f else .4f)
                Box {
                    Icon(icon, null, Modifier.size(22.dp), tint = color)
                    if (badgeCount != null && badgeCount > 0) Badge(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp),
                        containerColor = Color(0xFF005ACB), contentColor = Color.White) {
                        Text(if (badgeCount > 99) "99+" else badgeCount.toString())
                    }
                }
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                    color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
