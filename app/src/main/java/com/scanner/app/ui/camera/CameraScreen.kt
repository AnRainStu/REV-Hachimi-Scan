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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = with(chromeDensity) { topChrome.toDp() },
                bottom = with(chromeDensity) { bottomChrome.toDp() })) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                Icon(Icons.Default.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(40.dp))
                Text(stringResource(R.string.camera_permission_required), color = Color.White)
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.grant_permission)) }
                TextButton(onClick = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}")))
                }) { Text(stringResource(R.string.settings), color = Color(0xFF7AB8FF)) }
            }
            }
        }
        GlassSurface(Modifier.align(Alignment.TopCenter).onSizeChanged { topChrome = it.height }.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), dark = true) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateToReview, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.review), tint = Color.White) }
            Text(stringResource(R.string.camera_title), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { torch = !torch; viewModel.cameraControl?.enableTorch(torch) }, enabled = allowed && !busy && viewModel.cameraInfo?.hasFlashUnit() == true) {
                Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, stringResource(R.string.torch), tint = Color.White)
            }
            IconButton(onClick = onNavigateToSettings, enabled = !busy) { Icon(Icons.Default.Settings, stringResource(R.string.settings), tint = Color.White) }
        }
        }
        GlassSurface(Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomChrome = it.height }.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(), dark = true) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (stable) R.string.steady else R.string.hold_steady), style = MaterialTheme.typography.bodyMedium,
                    color = if (stable) Color(0xFF8BD7A9) else Color.White.copy(alpha = .75f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = curved, onClick = viewModel::toggleCurvedMode, enabled = !busy,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFF282A32), labelColor = Color.White,
                            selectedContainerColor = Color(0xFF174D83), selectedLabelColor = Color.White,
                            disabledLabelColor = Color.White.copy(alpha = .45f)),
                        label = { Text(stringResource(R.string.curved_mode)) })
                    if (hdr) AssistChip(onClick = {},
                        colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF282A32), labelColor = Color.White),
                        label = { Text(stringResource(R.string.full_hdr_title)) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { gallery.launch("image/*") }, enabled = !busy) { Text(stringResource(R.string.import_image), color = Color.White) }
                    FilledIconButton(onClick = { viewModel.capturePhoto(context, onNavigateToCrop) },
                        enabled = allowed && preview != null && !busy, modifier = Modifier.size(76.dp).border(3.dp, Color.White.copy(alpha = .5f), CircleShape).padding(6.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color(0xFF1D1D1F))) {
                        if (busy) CircularProgressIndicator(Modifier.size(28.dp))
                        else Icon(Icons.Default.PhotoCamera, stringResource(R.string.capture), modifier = Modifier.size(32.dp))
                    }
                    TextButton(onClick = onNavigateToReview, enabled = !busy) { Text(stringResource(R.string.review_with_count, count), color = Color.White) }
                }
                if (busy) Text(stringResource(if (hdr) R.string.fusing_full_hdr else R.string.processing), style = MaterialTheme.typography.bodySmall)
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
