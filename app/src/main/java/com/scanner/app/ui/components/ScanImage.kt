package com.scanner.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Decode on IO with a size cap so 50 MP captures never block composition or fill a grid at full resolution. */
@Composable
fun ScanImage(path: String, description: String, modifier: Modifier = Modifier, maxDimension: Int = 1200) {
    var loading by remember(path) { mutableStateOf(true) }
    val bitmap by produceState<ImageBitmap?>(null, path, maxDimension) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
        loading = false
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, description, Modifier.matchParentSize(), contentScale = ContentScale.Fit) }
            ?: if (loading) CircularProgressIndicator(Modifier.size(24.dp))
            else Icon(Icons.Default.BrokenImage, description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
