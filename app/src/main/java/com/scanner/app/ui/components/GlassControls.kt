package com.scanner.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A glass control with native semantics and interruption-safe, 120ms touch feedback. */
@Composable
fun GlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    primary: Boolean = false, cornerRadius: Dp = 32.dp, strong: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val context = LocalContext.current
    val motion = runCatching { Settings.Global.getFloat(context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(true)
    val scale by animateFloatAsState(if (motion && pressed && !focused) .97f else 1f,
        tween(120, easing = CubicBezierEasing(.23f, 1f, .32f, 1f)), label = "glass press")
    GlassSurface(modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .45f }
        .clickable(interactionSource = interactions, indication = ripple(), enabled = enabled,
            role = Role.Button, onClick = onClick), cornerRadius = cornerRadius, strong = strong || primary,
        tint = if (primary) Color(0xFF005ACB) else Color.Unspecified,
        contentColor = if (primary) Color.White else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.heightIn(min = 52.dp).padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content)
    }
}
