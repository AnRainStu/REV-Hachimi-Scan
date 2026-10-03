package com.scanner.app.ui.components

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Slow violet light on its own RenderNode; paper and controls never move with it. */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier, forceDark: Boolean = false) {
    val dark = forceDark || MaterialTheme.colorScheme.background.luminance() < .5f
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scale = rememberAnimatorScale()
    val powerSaving = rememberPowerSaveMode()
    val animate = scale > 0f && !powerSaving && !LocalReduceTransparency.current
    val phase = remember { mutableFloatStateOf(0f) }
    val lights = remember(dark) {
        listOf(
            softLight(Color(0xFF9F72E4), if (dark) .29f else .25f),
            softLight(Color(0xFFC4A6FA), if (dark) .24f else .34f),
            softLight(Color(0xFF858AE4), if (dark) .24f else .19f),
            softLight(Color(0xFFE9D8FF), if (dark) .16f else .28f)
        )
    }
    val base = remember(dark) { Brush.verticalGradient(if (dark)
        listOf(Color(0xFF17131F), Color(0xFF101016))
        else listOf(Color(0xFFFAF8FC), Color(0xFFF0EDF7))) }

    LaunchedEffect(lifecycle, animate, scale) {
        if (!animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // A resumed screen starts a fresh clock, preserving its current light position.
            var previousFrame = 0L
            val periodNanos = 28_000_000_000.0 * scale.coerceAtLeast(.5f)
            while (isActive) {
                withFrameNanos { frame ->
                    if (previousFrame == 0L) previousFrame = frame
                    val elapsed = frame - previousFrame
                    if (elapsed >= 33_333_333L) {
                        phase.floatValue = ((phase.floatValue + elapsed / periodNanos * 2 * PI) % (2 * PI)).toFloat()
                        previousFrame = frame
                    }
                }
            }
        }
    }

    // Bound the moving light node before it is sampled by the glass backdrop.
    Canvas(modifier.fillMaxSize().graphicsLayer { clip = true }) {
        // Read the clock only during drawing, keeping the page out of per-frame composition.
        val t = phase.floatValue
        val w = size.width
        val h = size.height
        drawRect(base)
        // A faint upper glow lets the navigation glass pick up the moving violet tint.
        lightBand(lights[0], Offset(w * (.85f + .08f * sin(t)), h * (.07f + .03f * cos(t))),
            w * .54f, h * .20f, 8f * sin(t), opacity = .45f)
        lightBand(lights[0], Offset(w * (.68f + .18f * sin(t)), h * (.25f + .10f * cos(t))),
            w * .92f, h * .15f, -28f + 8f * sin(t))
        lightBand(lights[1], Offset(w * (.24f + .22f * sin(t + 2.2f)), h * (.73f + .10f * sin(t + 1.1f))),
            w * 1.12f, h * .13f, 28f + 7f * cos(t + 1f))
        lightBand(lights[2], Offset(w * (.85f + .12f * cos(t + 1.4f)), h * (.92f + .08f * sin(t + 2f))),
            w * .80f, h * .17f, -37f + 8f * cos(t))
        lightBand(lights[3], Offset(w * (.36f + .16f * cos(t + .7f)), h * (.55f + .12f * sin(t + .4f))),
            w * 1.15f, h * .045f, -36f + 6f * sin(t + 1f))
    }
}

private fun softLight(color: Color, alpha: Float) = Brush.radialGradient(
    0f to color.copy(alpha = alpha), .28f to color.copy(alpha = alpha * .72f),
    .60f to color.copy(alpha = alpha * .24f), 1f to Color.Transparent,
    center = Offset.Zero, radius = 1f)

private fun DrawScope.lightBand(brush: Brush, center: Offset, radiusX: Float, radiusY: Float, angle: Float, opacity: Float = 1f) {
    withTransform({
        translate(center.x, center.y)
        rotate(angle, pivot = Offset.Zero)
        scale(radiusX, radiusY, pivot = Offset.Zero)
    }) { drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = opacity) }
}

/** Listen to live accessibility/developer animation changes, including adb changes in CI. */
@Composable
private fun rememberAnimatorScale(): Float {
    val resolver = LocalContext.current.contentResolver
    fun readScale() = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            .takeIf { it.isFinite() && it >= 0f } ?: 0f
    }.getOrDefault(0f)
    var scale by remember(resolver) { mutableFloatStateOf(readScale()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = readScale() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        // Close the gap between the initial read and registering the observer.
        scale = readScale()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}
