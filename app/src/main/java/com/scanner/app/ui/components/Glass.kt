package com.scanner.app.ui.components

import android.os.Build
import android.os.PowerManager
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
import androidx.compose.ui.unit.dp

private class Backdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}
private val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }
val LocalReduceTransparency = staticCompositionLocalOf { false }

/** Records only the content plane. Glass chrome never enters its own backdrop. */
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
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { backdrop.origin = it.positionInRoot() }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }) {
                GlassCanvas()
                content(PaddingValues(top = with(density) { topHeight.toDp() },
                    bottom = with(density) { bottomHeight.toDp() }))
            }
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topHeight = it.height }) { topBar() }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomHeight = it.height }) { bottomBar() }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp,
                bottom = with(density) { bottomHeight.toDp() } + 16.dp)) { floatingActionButton() }
        }
    }
}

@Composable
private fun GlassCanvas() {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < .5f
    Canvas(Modifier.fillMaxSize().background(colors.background)) {
        drawRect(Brush.radialGradient(listOf(Color(0xFF8EBBFF).copy(alpha = if (dark) .16f else .22f), Color.Transparent),
            center = Offset(size.width * .95f, size.height * .12f), radius = size.width * .9f))
        drawRect(Brush.radialGradient(listOf(Color(0xFFA5B1EF).copy(alpha = if (dark) .12f else .15f), Color.Transparent),
            center = Offset(0f, size.height * .8f), radius = size.width))
    }
}

/** GPU backdrop blur on Android 12+, with a legible material fallback on older devices.
 * Text and controls are separate siblings, so they are never blurred.
 * No bitmap readback, image decoding or continuously running animation is involved.
 */
@Composable
fun GlassSurface(modifier: Modifier = Modifier, dark: Boolean = false,
    content: @Composable BoxScope.() -> Unit) {
    val backdrop = LocalBackdrop.current
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    val reduced = LocalReduceTransparency.current || power?.isPowerSaveMode == true
    val blur = backdrop != null && Build.VERSION.SDK_INT >= 31 && !reduced
    val isDark = dark || MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(28.dp)
    val tint = if (isDark) Color(0xFF202126) else Color.White
    var origin by remember { mutableStateOf(Offset.Zero) }
    val radius = with(LocalDensity.current) { 24.dp.toPx() }
    Box(modifier.shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = .08f),
        spotColor = Color.Black.copy(alpha = .12f)).clip(shape)
        .onGloballyPositioned { origin = it.positionInRoot() }) {
        if (blur) Canvas(Modifier.matchParentSize().graphicsLayer {
            renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
        }) {
            backdrop?.let {
                val offset = it.origin - origin
                translate(offset.x, offset.y) { drawLayer(it.layer) }
            }
        }
        Box(Modifier.matchParentSize().background(tint.copy(alpha = when { reduced -> 1f; blur -> .72f; isDark -> .80f; else -> .94f }))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (isDark) .10f else .35f), Color.Transparent)))
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = if (isDark) .32f else .95f),
                Color.White.copy(alpha = .12f), Color.White.copy(alpha = .5f))), shape))
        CompositionLocalProvider(LocalContentColor provides if (isDark) Color(0xFFF5F5F7) else Color(0xFF1D1D1F)) { content() }
    }
}

@Composable
fun GlassDock(content: @Composable BoxScope.() -> Unit) {
    GlassSurface(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(title: @Composable () -> Unit, modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors()) {
    GlassSurface(modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
        TopAppBar(title = title, navigationIcon = navigationIcon, actions = actions,
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = colors.copy(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent))
    }
}
