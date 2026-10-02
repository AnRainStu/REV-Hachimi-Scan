package com.scanner.app.ui.camera

import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scanner.app.data.camera.FrameAnalyzer
import com.scanner.app.engine.NativeEdgeDetector
import java.util.concurrent.Executors

/** Owns CameraX binding and the analyzer executor for exactly one visible preview. */
@Composable
fun CameraPreview(viewModel: CameraViewModel, modifier: Modifier = Modifier, onReady: (PreviewView) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember(context) { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    AndroidView(factory = { previewView }, modifier = modifier)
    DisposableEffect(owner, previewView) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var capture: ImageCapture? = null
        var analysis: ImageAnalysis? = null
        future.addListener({
            if (!disposed) {
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    val previewUseCase = Preview.Builder().setResolutionSelector(
                        ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()
                    ).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val captureUseCase = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(95).setResolutionSelector(ResolutionSelector.Builder()
                            .setAspectRatioStrategy(ratio).setResolutionStrategy(ResolutionStrategy(android.util.Size(4080, 3060), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                        .build()
                    val analyzer = FrameAnalyzer(NativeEdgeDetector(), viewModel.curvedModeEnabled.value, viewModel::onFrameAnalyzed)
                    val analysisUseCase = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio)
                            .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                        .build().also { it.setAnalyzer(executor, analyzer) }
                    preview = previewUseCase; capture = captureUseCase; analysis = analysisUseCase
                    val camera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase, captureUseCase, analysisUseCase)
                    viewModel.imageCapture = captureUseCase; viewModel.imageAnalysis = analysisUseCase
                    viewModel.cameraControl = camera.cameraControl; viewModel.cameraInfo = camera.cameraInfo
                    viewModel.frameAnalyzer = analyzer
                    onReady(previewView)
                } catch (e: Exception) { viewModel.reportError(e.message ?: "Cannot start camera") }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            viewModel.cameraControl?.enableTorch(false)
            analysis?.clearAnalyzer()
            listOfNotNull(preview, capture, analysis).takeIf { it.isNotEmpty() }?.let { provider?.unbind(*it.toTypedArray()) }
            executor.shutdown()
            viewModel.imageCapture = null; viewModel.imageAnalysis = null
            viewModel.cameraControl = null; viewModel.cameraInfo = null; viewModel.frameAnalyzer = null
        }
    }
}
