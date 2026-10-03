package com.scanner.app.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private class Backdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

private val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }
val LocalReduceTransparency = staticCompositionLocalOf { false }

/** A single GPU recording of the content plane, excluding every glass control. */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer) { Backdrop(layer) }
    var topHeight by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val edgeColor = MaterialTheme.colorScheme.background
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()
                .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }) {
                AmbientBackground()
                content(PaddingValues(top = with(density) { topHeight.toDp() },
                    bottom = maxOf(navigationInset, with(density) { bottomHeight.toDp() })))
                // Scrolling paper must not compete with the system time and status icons.
                Canvas(Modifier.fillMaxWidth().height(statusInset + 12.dp)) {
                    drawRect(Brush.verticalGradient(0f to edgeColor, .67f to edgeColor,
                        1f to edgeColor.copy(alpha = 0f)))
                }
            }
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topHeight = it.height }) { topBar() }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomHeight = it.height }) { bottomBar() }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp,
                bottom = with(density) { bottomHeight.toDp() } + 16.dp)) { floatingActionButton() }
        }
    }
}

/**
 * Clear liquid material: a captured backdrop with localized edge refraction on Android 13+.
 * Android 12 uses real GPU backdrop blur; older/native preview surfaces use a translucent
 * material. Foreground content is a separate sibling and is never passed through the effect.
 * Recording stays on the GPU: no bitmap readback or time-driven optical shader.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    cornerRadius: Dp = 28.dp,
    strong: Boolean = false,
    contentColor: Color = Color.Unspecified,
    tint: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = LocalBackdrop.current
    val reduced = LocalReduceTransparency.current || rememberPowerSaveMode()
    val captured = backdrop != null && Build.VERSION.SDK_INT >= 31 && !reduced
    val isDark = dark || MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val density = LocalDensity.current
    val radiusPx = with(density) { cornerRadius.toPx() }
    val lens = remember(captured) {
        if (captured && Build.VERSION.SDK_INT >= 33) LiquidLens() else null
    }
    val diffusion = with(density) { (if (strong) 8.dp else 4.dp).toPx() }
    val foreground = if (contentColor != Color.Unspecified) contentColor
        else if (isDark) Color(0xFFF8F9FC) else Color(0xFF1B1E24)
    val hasTint = tint != Color.Unspecified
    // A colored primary action carries a denser material than clear navigation controls.
    val material = if (hasTint) tint else if (isDark) Color(0xFF151C29) else Color.White
    val opacity = when {
        reduced -> 1f
        hasTint -> if (strong) .92f else .86f
        captured && lens != null && isDark -> if (strong) .46f else .30f
        captured && lens != null -> if (strong) .34f else .16f
        // Blur-only and native-preview paths cannot bound backdrop luminance like AGSL.
        // Denser fallback material keeps labels readable over both white paper and dark photos.
        isDark -> if (strong) .84f else .74f
        else -> if (strong) .92f else .82f
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier
        .shadow(if (strong) 20.dp else 14.dp, shape,
            ambientColor = Color(0xFF18243F).copy(alpha = if (isDark) .25f else .10f),
            spotColor = Color(0xFF18243F).copy(alpha = if (isDark) .30f else .15f))
        .clip(shape)
        .onGloballyPositioned { origin = it.positionInRoot() }) {
        if (captured) {
            Canvas(Modifier.matchParentSize().graphicsLayer {
                renderEffect = if (Build.VERSION.SDK_INT >= 33 && lens != null) {
                    lens.effect(size.width, size.height, radiusPx, density.density, strong, isDark)
                } else BlurEffect(diffusion, diffusion, TileMode.Clamp)
            }) {
                backdrop?.let {
                    // Both coordinates use this Compose root; each scaffold owns a separate plane.
                    val offset = it.origin - origin
                    translate(offset.x, offset.y) { drawLayer(it.layer) }
                }
            }
        }
        Box(Modifier.matchParentSize()
            .background(material.copy(alpha = opacity))
            .background(Brush.verticalGradient(listOf(
                Color.White.copy(alpha = if (reduced) 0f else if (isDark || hasTint) .07f else .12f),
                Color.Transparent,
                Color(0xFF667AAA).copy(alpha = if (reduced) 0f else .025f))))
            .border(.75.dp, Brush.linearGradient(listOf(
                Color.White.copy(alpha = if (isDark || hasTint) .64f else .96f),
                Color.White.copy(alpha = if (isDark || hasTint) .12f else .24f),
                Color(0xFF6B7DA5).copy(alpha = if (isDark) .18f else .12f),
                Color.White.copy(alpha = if (isDark || hasTint) .42f else .78f))), shape))
        // A second, inset rim produces a curved bevel rather than a flat outlined rectangle.
        if (!reduced) Box(Modifier.matchParentSize().padding(1.5.dp)
            .border(.5.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (isDark) .14f else .36f),
                Color.Transparent, Color.White.copy(alpha = .10f))), RoundedCornerShape((cornerRadius - 1.5.dp).coerceAtLeast(0.dp))))
        CompositionLocalProvider(LocalContentColor provides foreground) { content() }
    }
}

@Composable
internal fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var saving by remember(power) { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(context, power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                saving = power?.isPowerSaveMode == true
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return saving
}

@Composable
fun GlassDock(content: @Composable BoxScope.() -> Unit) {
    GlassSurface(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(),
        strong = true, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors()
) {
    GlassSurface(modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth()) {
        TopAppBar(title = title, navigationIcon = navigationIcon, actions = actions,
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = colors.copy(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent))
    }
}

@RequiresApi(33)
private class LiquidLens {
    private val shader = RuntimeShader(LIQUID_LENS_SHADER)

    fun effect(width: Float, height: Float, radius: Float, density: Float, strong: Boolean, dark: Boolean): RenderEffect {
        shader.setFloatUniform("resolution", width.coerceAtLeast(1f), height.coerceAtLeast(1f))
        shader.setFloatUniform("cornerRadius", radius.coerceAtMost(minOf(width, height) * .5f))
        shader.setFloatUniform("density", density)
        shader.setFloatUniform("diffusion", if (strong) 2.4f else 1.25f)
        shader.setFloatUniform("materialDark", if (dark) 1f else 0f)
        val refracted = AndroidRenderEffect.createRuntimeShaderEffect(shader, "backdrop")
        return if (strong) {
            // Defocus text before bending the edge; a few point samples leave doubled letters.
            val blur = AndroidRenderEffect.createBlurEffect(8f * density, 8f * density,
                android.graphics.Shader.TileMode.CLAMP)
            AndroidRenderEffect.createChainEffect(refracted, blur).asComposeRenderEffect()
        } else refracted.asComposeRenderEffect()
    }
}

/** The edge alone bends the image; the broad center remains stable and clear. */
private const val LIQUID_LENS_SHADER = """
    uniform shader backdrop;
    uniform float2 resolution;
    uniform float cornerRadius;
    uniform float density;
    uniform float diffusion;
    uniform float materialDark;

    float roundedDistance(float2 point) {
        float2 q = abs(point - resolution * 0.5) - resolution * 0.5 + cornerRadius;
        return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - cornerRadius;
    }

    half4 sampleBackdrop(float2 point) {
        return backdrop.eval(clamp(point, float2(0.5), resolution - float2(0.5)));
    }

    half4 main(float2 point) {
        float distance = roundedDistance(point);
        float2 gradient = float2(
            roundedDistance(point + float2(0.5, 0.0)) - roundedDistance(point - float2(0.5, 0.0)),
            roundedDistance(point + float2(0.0, 0.5)) - roundedDistance(point - float2(0.0, 0.5)));
        float2 normal = gradient / max(length(gradient), 0.001);
        float edge = 1.0 - smoothstep(0.0, 14.0 * density, -distance);
        float2 lensPoint = point - normal * edge * edge * 7.0 * density;
        float spread = density * (diffusion + edge * 0.65);
        half4 color = sampleBackdrop(lensPoint) * 0.40;
        color += sampleBackdrop(lensPoint + float2(spread, 0.0)) * 0.15;
        color += sampleBackdrop(lensPoint - float2(spread, 0.0)) * 0.15;
        color += sampleBackdrop(lensPoint + float2(0.0, spread)) * 0.15;
        color += sampleBackdrop(lensPoint - float2(0.0, spread)) * 0.15;
        half luminance = dot(color.rgb, half3(0.2126, 0.7152, 0.0722));
        color.rgb = mix(half3(luminance), color.rgb, 1.15);
        // Vibrancy bounds keep the foreground readable when a dark photo or bright paper
        // passes below a control, while already suitable backgrounds stay transparent.
        if (materialDark > 0.5) {
            color.rgb *= min(1.0, 0.54 / max(float(luminance), 0.001));
        } else {
            float lift = clamp((0.50 - float(luminance)) / max(1.0 - float(luminance), 0.001), 0.0, 1.0);
            color.rgb = mix(color.rgb, half3(1.0), lift);
        }
        float light = dot(normal, normalize(float2(-0.65, -0.76)));
        float rim = pow(edge, 3.0);
        color.rgb += half3(max(light, 0.0) * rim * 0.13);
        color.rgb -= half3(max(-light, 0.0) * rim * 0.045);
        return half4(clamp(color.rgb, 0.0, 1.0), color.a);
    }
"""
