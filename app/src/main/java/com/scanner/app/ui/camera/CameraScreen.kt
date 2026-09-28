package com.scanner.app.ui.camera

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanner.app.R
import com.scanner.app.domain.model.DetectionResult
import com.scanner.app.engine.NativeEdgeDetector
import com.scanner.app.ui.theme.PrismCyan
import com.scanner.app.ui.theme.SteadyEmerald
import com.scanner.app.ui.theme.SteadyAmber
import java.util.concurrent.Executors

@Composable
fun CameraScreen(
    onNavigateToReview: () -> Unit,
    onNavigateToCrop: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: CameraViewModel = viewModel()
) {
    val detectedQuad by viewModel.detectedQuad.collectAsState()
    val curvedModeEnabled by viewModel.curvedModeEnabled.collectAsState()
    val burstModeEnabled by viewModel.burstModeEnabled.collectAsState()
    val isStable by viewModel.isStable.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val pageCount by viewModel.pageCount.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Animated stability pulse effect
    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Shutter outer ring color based on stability & burst mode
    val targetRingColor = when {
        burstModeEnabled -> PrismCyan
        isStable -> SteadyEmerald
        else -> Color.White.copy(alpha = 0.45f)
    }
    val ringColor by animateColorAsState(
        targetValue = targetRingColor,
        animationSpec = tween(durationMillis = 250),
        label = "stabilityRingColor"
    )
    val ringWidth by animateDpAsState(
        targetValue = if (isStable || burstModeEnabled) 4.dp else 2.5.dp,
        animationSpec = tween(durationMillis = 250),
        label = "ringWidth"
    )

    // Shutter press spring animation
    val shutterInteractionSource = remember { MutableInteractionSource() }
    val isShutterPressed by shutterInteractionSource.collectIsPressedAsState()
    val shutterScale by animateFloatAsState(
        targetValue = if (isShutterPressed) 0.92f else 1.0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "shutterScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Fullscreen Viewfinder (Edge-to-Edge)
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageCapture = ImageCapture.Builder().build()
                    viewModel.imageCapture = imageCapture

                    val edgeDetector = NativeEdgeDetector()
                    val frameAnalyzer = com.scanner.app.data.camera.FrameAnalyzer(
                        detector = edgeDetector,
                        curvedMode = curvedModeEnabled
                    ) { result ->
                        viewModel.onFrameAnalyzed(result)
                    }
                    viewModel.frameAnalyzer = frameAnalyzer

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                        .also {
                            val analyzerExecutor = Executors.newSingleThreadExecutor()
                            it.setAnalyzer(analyzerExecutor, frameAnalyzer)
                        }
                    viewModel.imageAnalysis = imageAnalysis

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, executor)

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Real-time Document Edge Overlay
        detectedQuad?.let { quad ->
            EdgeOverlay(
                detectionResult = quad,
                isCurvedMode = curvedModeEnabled,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. Top Floating Status & Action Bar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Frosted pill badge indicating stability
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x990A0F1D))
                    .border(1.dp, Color(0x2AFFFFFF), RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    val dotColor = if (isStable) SteadyEmerald else SteadyAmber
                    val effectiveAlpha = if (isStable) pulseAlpha else 1.0f
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(dotColor.copy(alpha = effectiveAlpha), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isStable) stringResource(R.string.steady) else stringResource(R.string.hold_steady),
                        color = if (isStable) SteadyEmerald else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp
                    )
                }
            }

            // Frosted Settings Button
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0x990A0F1D))
                    .border(1.dp, Color(0x2AFFFFFF), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 4. Center Processing / Burst Fusing Modal
        if (isCapturing) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xE60F172A))
                    .border(1.dp, if (burstModeEnabled) PrismCyan.copy(alpha = 0.5f) else Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = if (burstModeEnabled) PrismCyan else SteadyEmerald,
                        strokeWidth = 3.5.dp,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (burstModeEnabled) stringResource(R.string.fusing_burst) else stringResource(R.string.processing),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.sp
                    )
                }
            }
        }

        // 5. Bottom Controls (Mode Toggles + Pro Shutter Bar)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Secondary Controls: Mode Toggle Capsules
            Row(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Burst Mode Capsule
                val burstBg = if (burstModeEnabled) PrismCyan.copy(alpha = 0.22f) else Color(0x770A0F1D)
                val burstBorder = if (burstModeEnabled) PrismCyan else Color(0x2EFFFFFF)
                val burstTextColor = if (burstModeEnabled) PrismCyan else Color(0xFFE2E8F0)

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(burstBg)
                        .border(1.2.dp, burstBorder, RoundedCornerShape(24.dp))
                        .clickable { viewModel.toggleBurstMode() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (burstModeEnabled) PrismCyan else Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (burstModeEnabled) stringResource(R.string.burst_on) else stringResource(R.string.burst_off),
                            fontSize = 12.sp,
                            fontWeight = if (burstModeEnabled) FontWeight.Bold else FontWeight.Medium,
                            color = burstTextColor
                        )
                    }
                }

                // Curved Dewarp Mode Capsule
                val curveBg = if (curvedModeEnabled) SteadyEmerald.copy(alpha = 0.22f) else Color(0x770A0F1D)
                val curveBorder = if (curvedModeEnabled) SteadyEmerald else Color(0x2EFFFFFF)
                val curveTextColor = if (curvedModeEnabled) SteadyEmerald else Color(0xFFE2E8F0)

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(curveBg)
                        .border(1.2.dp, curveBorder, RoundedCornerShape(24.dp))
                        .clickable { viewModel.toggleCurvedMode() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CropFree,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (curvedModeEnabled) SteadyEmerald else Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (curvedModeEnabled) stringResource(R.string.curve_on) else stringResource(R.string.curve_off),
                            fontSize = 12.sp,
                            fontWeight = if (curvedModeEnabled) FontWeight.Bold else FontWeight.Medium,
                            color = curveTextColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Primary Bottom Controls Row: Symmetrical Balance & Tactile Shutter
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left balanced anchor
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Left subtle placeholder to maintain perfect center alignment
                }

                // Center Dual-Ring Tactile Shutter Button
                Box(
                    modifier = Modifier
                        .size(86.dp)
                        .border(width = ringWidth, color = ringColor, shape = CircleShape)
                        .padding(6.dp)
                        .scale(shutterScale),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(54.dp),
                            color = if (burstModeEnabled) PrismCyan else SteadyEmerald,
                            strokeWidth = 3.5.dp
                        )
                    } else {
                        val innerColor = if (burstModeEnabled) PrismCyan else Color.White
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(innerColor)
                                .clickable(
                                    interactionSource = shutterInteractionSource,
                                    indication = null
                                ) {
                                    viewModel.capturePhoto(context) { pageId ->
                                        onNavigateToCrop(pageId)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (burstModeEnabled) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = stringResource(R.string.capture),
                                    tint = Color(0xFF0F172A),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                // Right: Document Gallery Preview Button with Count Badge
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (pageCount > 0) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0x990A0F1D))
                                .border(1.5.dp, Color(0x44FFFFFF), RoundedCornerShape(16.dp))
                                .clickable { onNavigateToReview() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Collections,
                                contentDescription = stringResource(R.string.review),
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )

                            // Page count badge pill
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .clip(CircleShape)
                                    .background(SteadyEmerald)
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$pageCount",
                                    color = Color(0xFF0F172A),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
